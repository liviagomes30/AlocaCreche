package org.alocacreche.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
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

    private OutboxPublicadorScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublicadorScheduler(service);
    }

    @Test
    @DisplayName("sem eventos pendentes, nao chama publicarEvento")
    void semEventosPendentesNaoChamaPublicarEvento() {
        when(service.buscarPendentes()).thenReturn(Collections.emptyList());

        scheduler.executar();

        verify(service, never()).publicarEvento(any());
    }

    @Test
    @DisplayName("chama publicarEvento uma vez por evento pendente")
    void chamaPublicarEventoUmaVezPorEventoPendente() {
        OutboxEvent evento1 = new OutboxEvent("vaga.liberada", "{}");
        OutboxEvent evento2 = new OutboxEvent("fila.atualizada", "{}");
        when(service.buscarPendentes()).thenReturn(List.of(evento1, evento2));

        scheduler.executar();

        verify(service, times(1)).publicarEvento(evento1);
        verify(service, times(1)).publicarEvento(evento2);
    }

    @Test
    @DisplayName("falha inesperada num evento nao impede o processamento dos demais do mesmo poll")
    void falhaInesperadaNumEventoNaoImpedeProcessamentoDosDemais() {
        OutboxEvent eventoComFalha = new OutboxEvent("vaga.liberada", "{}");
        OutboxEvent eventoOk = new OutboxEvent("fila.atualizada", "{}");
        when(service.buscarPendentes()).thenReturn(List.of(eventoComFalha, eventoOk));
        doThrow(new RuntimeException("falha inesperada, ex.: erro de banco ao salvar"))
                .when(service)
                .publicarEvento(eventoComFalha);

        assertThatCode(() -> scheduler.executar()).doesNotThrowAnyException();

        verify(service, times(1)).publicarEvento(eventoComFalha);
        verify(service, times(1)).publicarEvento(eventoOk);
    }

    @Test
    @DisplayName("flag alocacreche.outbox.publicador.habilitado: ligado por padrao, desligado so com false")
    void flagLigaEDesligaOBeanDoScheduler() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withBean(OutboxPublicadorService.class, () -> mock(OutboxPublicadorService.class))
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
