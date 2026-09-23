package org.alocacreche.relatorios.matricula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.alocacreche.relatorios.config.RabbitMqConfig;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ponta a ponta com RabbitMQ e Postgres reais (Testcontainers) - mesmo padrao de
 * FilaEsperaAtualizadaConsumerIT/VagaLiberadaConsumerIT, fechando o TODO registrado em
 * .specs/STATE.md (feature relatorios-matricula, "next step").
 *
 * <p>Payload malformado (0 invocacoes do listener) e validacao de negocio invalida (1 invocacao,
 * excecao dentro de receber()) sao distinguidos pela contagem de invocacao do listener via spy, nao
 * so pela chegada na DLQ - ambas as condicoes chegam na DLQ tanto com rejeicao imediata quanto com 3
 * tentativas (ver .specs/STATE.md AD-001).
 */
@SpringBootTest
@Testcontainers
class MatriculaAtualizadaConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @Container static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired private RabbitTemplate rabbitTemplate;

    @MockitoSpyBean private MatriculaRelatorioRepository repository;

    @MockitoSpyBean private MatriculaAtualizadaListener listener;

    @Autowired private PlatformTransactionManager transactionManager;

    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            TenantContext.aplicar(entityManager, 1L);
                            entityManager.createNativeQuery("DELETE FROM matricula_relatorio").executeUpdate();
                        });
        drenarFila(RabbitMqConfig.MATRICULA_QUEUE);
        drenarFila(RabbitMqConfig.MATRICULA_DLQ);
    }

    private void drenarFila(String fila) {
        while (rabbitTemplate.receive(fila, 200) != null) {
            // esvazia qualquer mensagem residual de um teste anterior - mesma tecnica de
            // VagaLiberadaConsumerIT/FilaEsperaAtualizadaConsumerIT.
        }
    }

    @Test
    void eventoValidoAtualizaProjecao() throws InterruptedException {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        1L,
                        300L,
                        30L,
                        900L,
                        950L,
                        StatusMatricula.PENDENTE,
                        1.5,
                        LocalDate.now().minusDays(2),
                        null,
                        LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.MATRICULA_ATUALIZADA_ROUTING_KEY, evento);

        MatriculaRelatorio matricula = buscarComRetry(950L, 1L, 5_000);
        assertThat(matricula).isNotNull();
        assertThat(matricula.getInscricaoId()).isEqualTo(900L);
        assertThat(matricula.getTurmaId()).isEqualTo(300L);
        assertThat(matricula.getCrecheId()).isEqualTo(30L);
        assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.PENDENTE);
        assertThat(matricula.getDistanciaKm()).isEqualTo(1.5);
        assertThat(matricula.getUltimaSequenciaAplicada()).isEqualTo(1L);
    }

    @Test
    void payloadMalformadoVaiDiretoParaDlqSemInvocarOListener() {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message mensagemInvalida =
                new Message(
                        "isto nao e um json valido {{{".getBytes(StandardCharsets.UTF_8),
                        messageProperties);

        rabbitTemplate.send(
                RabbitMqConfig.DOMINIO_EXCHANGE,
                RabbitMqConfig.MATRICULA_ATUALIZADA_ROUTING_KEY,
                mensagemInvalida);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.MATRICULA_DLQ, 5_000);
        assertThat(naDlq).isNotNull();

        Message naFilaPrincipal = rabbitTemplate.receive(RabbitMqConfig.MATRICULA_QUEUE, 500);
        assertThat(naFilaPrincipal).isNull();
        // A excecao de conversao acontece antes do metodo do listener ser chamado - 0 invocacoes e
        // o sinal correto (distinto do caso de distanciaKm invalida abaixo, onde o listener e
        // invocado 1 vez e a excecao acontece dentro de receber()).
        verify(listener, never()).receber(any());
    }

    @Test
    void distanciaKmNegativaVaiDiretoParaDlqComUmaUnicaInvocacaoDoListener() {
        Long matriculaId = 951L;
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        2L,
                        "MatriculaAtualizada",
                        1L,
                        301L,
                        31L,
                        901L,
                        matriculaId,
                        StatusMatricula.PENDENTE,
                        -1.0,
                        LocalDate.now().minusDays(1),
                        null,
                        LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.MATRICULA_ATUALIZADA_ROUTING_KEY, evento);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.MATRICULA_DLQ, 5_000);
        assertThat(naDlq).isNotNull();
        verify(repository, never())
                .upsert(eq(matriculaId), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // Sinal decisivo: "repositorio nunca chamado" tambem seria verdade com 3 tentativas antes
        // da DLQ (a excecao acontece antes do repositorio ser tocado em cada tentativa). So a
        // contagem de invocacoes do listener distingue rejeicao imediata (1 chamada) de
        // retry-entao-DLQ (3 chamadas) - ver .specs/STATE.md AD-001.
        verify(listener, times(1)).receber(argThat(e -> matriculaId.equals(e.matriculaId())));
    }

    @Test
    void falhaTransitoriaVaiParaDlqApenasAposTresTentativas() {
        Long matriculaId = 952L;
        doThrow(new RuntimeException("falha transitoria simulada"))
                .when(repository)
                .upsert(eq(matriculaId), any(), any(), any(), any(), any(), any(), any(), any(), any());

        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        3L,
                        "MatriculaAtualizada",
                        1L,
                        302L,
                        32L,
                        902L,
                        matriculaId,
                        StatusMatricula.PENDENTE,
                        2.0,
                        LocalDate.now().minusDays(1),
                        null,
                        LocalDateTime.now());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.MATRICULA_ATUALIZADA_ROUTING_KEY, evento);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.MATRICULA_DLQ, 5_000);
        assertThat(naDlq).isNotNull();
        verify(repository, times(3))
                .upsert(eq(matriculaId), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private MatriculaRelatorio buscarComRetry(Long matriculaId, Long municipioId, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            MatriculaRelatorio matricula =
                    new TransactionTemplate(transactionManager)
                            .execute(
                                    status -> {
                                        TenantContext.aplicar(entityManager, municipioId);
                                        return entityManager.find(MatriculaRelatorio.class, matriculaId);
                                    });
            if (matricula != null) {
                return matricula;
            }
            Thread.sleep(200);
        }
        return null;
    }
}
