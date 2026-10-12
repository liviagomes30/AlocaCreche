package org.alocacreche.core.outbox.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.alocacreche.core.outbox.service.OutboxPublicadorControle;
import org.alocacreche.core.outbox.service.OutboxPublicadorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Cobre so a orquestracao do loop (chama o service uma vez por evento pendente, via bean externo -
 * nunca this.publicarEvento) - a prova de que o @Transactional de fato funciona por evento e do
 * @Scheduled disparar sozinho fica com OutboxPublicadorConsumerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublicadorScheduler")
class OutboxPublicadorSchedulerTest {

    @Mock private OutboxPublicadorService service;

    private final OutboxPublicadorControle controle = new OutboxPublicadorControle(true);

    private OutboxPublicadorScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublicadorScheduler(service, controle);
    }

    @Test
    @DisplayName("cada ciclo delega ao service um unico processamento do lote (a transacao vive la)")
    void cadaCicloDelegaUmProcessamentoDoLote() {
        scheduler.executar();

        verify(service, times(1)).processarPendentes();
    }

    @Test
    @DisplayName("com o publicador pausado, o ciclo nao processa nada; ao retomar, volta a processar")
    void pausadoNaoProcessaERetomaDepois() {
        controle.definirPausado(true);
        scheduler.executar();
        verify(service, never()).processarPendentes();

        controle.definirPausado(false);
        scheduler.executar();
        verify(service, times(1)).processarPendentes();
    }

    @Test
    @DisplayName("falha inesperada no ciclo nao propaga (o proximo ciclo continua agendado)")
    void falhaInesperadaNoCicloNaoPropaga() {
        doThrow(new RuntimeException("falha inesperada, ex.: erro de banco"))
                .when(service)
                .processarPendentes();

        assertThatCode(() -> scheduler.executar()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("flag alocacreche.outbox.publicador.habilitado: ligado por padrao, desligado so com false")
    void flagLigaEDesligaOBeanDoScheduler() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withBean(OutboxPublicadorService.class, () -> mock(OutboxPublicadorService.class))
                        .withBean(OutboxPublicadorControle.class, () -> new OutboxPublicadorControle(false))
                        .withUserConfiguration(OutboxPublicadorScheduler.class);

        runner.run(contexto -> assertThat(contexto).hasSingleBean(OutboxPublicadorScheduler.class));
        runner.withPropertyValues("alocacreche.outbox.publicador.habilitado=true")
                .run(contexto -> assertThat(contexto).hasSingleBean(OutboxPublicadorScheduler.class));
        runner.withPropertyValues("alocacreche.outbox.publicador.habilitado=false")
                .run(contexto -> assertThat(contexto).doesNotHaveBean(OutboxPublicadorScheduler.class));
    }

    @Test
    @DisplayName("timeout de confirmacao do publicador vem da propriedade, com padrao de 5000 ms")
    void timeoutDeConfirmacaoVemDaPropriedade() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
                        .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
                        .withBean(ObjectMapper.class, ObjectMapper::new)
                        .withUserConfiguration(OutboxPublicadorService.class);

        runner.run(
                contexto ->
                        assertThat(
                                        ReflectionTestUtils.getField(
                                                contexto.getBean(OutboxPublicadorService.class),
                                                "confirmacaoTimeoutMs"))
                                .isEqualTo(5000L));
        runner.withPropertyValues("alocacreche.outbox.publicador.confirmacao-timeout-ms=1234")
                .run(
                        contexto ->
                                assertThat(
                                                ReflectionTestUtils.getField(
                                                        contexto.getBean(OutboxPublicadorService.class),
                                                        "confirmacaoTimeoutMs"))
                                        .isEqualTo(1234L));
    }
}
