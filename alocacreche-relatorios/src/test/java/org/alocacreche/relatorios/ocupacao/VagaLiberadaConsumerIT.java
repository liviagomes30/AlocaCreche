package org.alocacreche.relatorios.ocupacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.alocacreche.relatorios.config.RabbitMqConfig;
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
 * Ponta a ponta com RabbitMQ e Postgres reais (Testcontainers). Cobre OCUP-01/04/05 (caminho
 * feliz), OCUP-06 (payload malformado direto pra DLQ) e OCUP-08/09 (falha transitoria - 3
 * tentativas, depois DLQ).
 */
@SpringBootTest
@Testcontainers
class VagaLiberadaConsumerIT {

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

    @MockitoSpyBean private OcupacaoTurmaRepository repository;

    @Autowired private PlatformTransactionManager transactionManager;

    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            entityManager
                                    .createNativeQuery("SET LOCAL app.current_tenant = '1'")
                                    .executeUpdate();
                            entityManager.createNativeQuery("DELETE FROM ocupacao_turma").executeUpdate();
                        });
        rabbitTemplate.receive(RabbitMqConfig.OCUPACAO_QUEUE, 200);
        rabbitTemplate.receive(RabbitMqConfig.OCUPACAO_DLQ, 200);
    }

    @Test
    void eventoValidoAtualizaProjecao() throws InterruptedException {
        VagaLiberadaEvent evento =
                new VagaLiberadaEvent(1L, "VagaLiberada", 1L, 300L, 30L, 6, LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.VAGA_LIBERADA_ROUTING_KEY, evento);

        OcupacaoTurma turma = buscarComRetry(300L, 1L, 5_000);
        assertThat(turma).isNotNull();
        assertThat(turma.getVagasDisponiveis()).isEqualTo(6);
        assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(1L);
    }

    @Test
    void payloadMalformadoVaiDiretoParaDlqSemReentrega() {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message mensagemInvalida =
                new Message(
                        "isto nao e um json valido {{{".getBytes(StandardCharsets.UTF_8),
                        messageProperties);

        rabbitTemplate.send(
                RabbitMqConfig.DOMINIO_EXCHANGE,
                RabbitMqConfig.VAGA_LIBERADA_ROUTING_KEY,
                mensagemInvalida);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.OCUPACAO_DLQ, 5_000);
        assertThat(naDlq).isNotNull();

        Message naFilaPrincipal = rabbitTemplate.receive(RabbitMqConfig.OCUPACAO_QUEUE, 500);
        assertThat(naFilaPrincipal).isNull();
    }

    @Test
    void payloadValidoComVagasNegativasVaiDiretoParaDlqSemRetentarNoRepositorio() {
        Long turmaId = 500L;
        VagaLiberadaEvent evento =
                new VagaLiberadaEvent(1L, "VagaLiberada", 1L, turmaId, 50L, -1, LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.VAGA_LIBERADA_ROUTING_KEY, evento);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.OCUPACAO_DLQ, 5_000);
        assertThat(naDlq).isNotNull();
        verify(repository, never()).upsert(eq(turmaId), any(), any(), any(), any());
    }

    @Test
    void falhaTransitoriaVaiParaDlqApenasAposTresTentativas() {
        Long turmaId = 400L;
        doThrow(new RuntimeException("falha transitoria simulada"))
                .when(repository)
                .upsert(eq(turmaId), any(), any(), any(), any());

        VagaLiberadaEvent evento =
                new VagaLiberadaEvent(1L, "VagaLiberada", 1L, turmaId, 40L, 2, LocalDateTime.now());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.VAGA_LIBERADA_ROUTING_KEY, evento);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.OCUPACAO_DLQ, 5_000);
        assertThat(naDlq).isNotNull();
        verify(repository, times(3)).upsert(eq(turmaId), any(), any(), any(), any());
    }

    private OcupacaoTurma buscarComRetry(Long turmaId, Long municipioId, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            OcupacaoTurma turma =
                    new TransactionTemplate(transactionManager)
                            .execute(
                                    status -> {
                                        entityManager
                                                .createNativeQuery(
                                                        "SET LOCAL app.current_tenant = '" + municipioId + "'")
                                                .executeUpdate();
                                        return entityManager.find(OcupacaoTurma.class, turmaId);
                                    });
            if (turma != null) {
                return turma;
            }
            Thread.sleep(200);
        }
        return null;
    }
}
