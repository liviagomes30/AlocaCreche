package org.alocacreche.core.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cobre OUTPUB-05/06 (evento nunca se perde quando o broker esta indisponivel) em classe propria -
 * este teste DERRUBA o broker deliberadamente, o que corromperia a conexao compartilhada e
 * contaminaria OutboxPublicadorConsumerIT se dividissem o mesmo container (mesmo motivo de
 * PainelPublicoFailOpenIT em alocacreche-relatorios).
 *
 * <p>Usa {@code rabbitmqctl stop_app}/{@code start_app} dentro do container, nao
 * {@code rabbitmq.stop()}/{@code start()} do Testcontainers - o container do Testcontainers, ao
 * reiniciar, sorteia uma NOVA porta mapeada (confirmado empiricamente), e a fabrica de conexao do
 * Spring fica presa na porta capturada no startup da aplicacao, nunca reconectando. `stop_app`
 * derruba so a aplicacao RabbitMQ (fecha o listener AMQP), mantendo o container e a porta mapeada
 * estaveis - a mesma tecnica documentada pelo proprio RabbitMQ para testes de disponibilidade.
 */
@SpringBootTest(properties = "alocacreche.outbox.publicador.habilitado=true")
@Testcontainers
class OutboxPublicadorBrokerIndisponivelIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

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

    @Autowired private OutboxEventRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private TopicExchange dominioExchange;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);

        Queue filaTeste = new Queue("relatorios.ocupacao.indisponivel", true);
        rabbitAdmin.declareQueue(filaTeste);
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(filaTeste)
                        .to(dominioExchange)
                        .with(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY));
    }

    @Test
    @DisplayName("sem broker disponivel, evento permanece PENDENTE e e publicado quando o broker volta")
    void semBrokerDisponivelEventoPermanecePendenteEDepoisPublicaQuandoBrokerVolta()
            throws InterruptedException, IOException {
        rabbitmq.execInContainer("rabbitmqctl", "stop_app");

        Long id = inserirPendente("vaga.liberada", "{\"turmaId\":301}");
        Thread.sleep(2_500);
        assertThat(buscarStatus(id)).isEqualTo("PENDENTE");

        rabbitmq.execInContainer("rabbitmqctl", "start_app");

        assertThat(buscarStatusComRetry(id, 15_000)).isEqualTo("PROCESSADO");
    }

    private Long inserirPendente(String tipoEvento, String payload) {
        return transactionTemplate.execute(
                status -> repository.save(new OutboxEvent(tipoEvento, payload)).getId());
    }

    private String buscarStatus(Long id) {
        return transactionTemplate.execute(
                status -> repository.findById(id).orElseThrow().getStatus());
    }

    private String buscarStatusComRetry(Long id, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String status = buscarStatus(id);
        while (!"PROCESSADO".equals(status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            status = buscarStatus(id);
        }
        return status;
    }
}
