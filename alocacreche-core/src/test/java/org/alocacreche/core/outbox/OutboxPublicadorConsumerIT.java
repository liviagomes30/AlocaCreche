package org.alocacreche.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
 * Ponta a ponta com Postgres e RabbitMQ reais (Testcontainers) - cobre OUTPUB-01/02/03/04/07/08/09.
 * OUTPUB-05/06 (broker indisponivel e recuperacao) ficam em {@link
 * OutboxPublicadorBrokerIndisponivelIT}, classe propria - esse teste derruba o container RabbitMQ
 * deliberadamente, o que corrompe a conexao compartilhada e contaminaria os demais testes desta
 * classe se dividissem o mesmo container (mesmo motivo de PainelPublicoFailOpenIT em
 * alocacreche-relatorios). Espera o poll {@code @Scheduled} (1s) disparar sozinho, nunca invoca
 * {@code executar()} manualmente - e a unica forma de provar que {@code @EnableScheduling} + a
 * injecao dos beans estao de fato conectados (mesmo racional do gotcha "RabbitAdmin so declara
 * topologia quando algo usa o broker de verdade", ver .specs/STATE.md AD-001).
 *
 * <p>A fila de teste abaixo replica a fila/binding reais de {@code alocacreche-relatorios}
 * (exchange {@code alocacreche.dominio}, routing key {@code vaga.liberada}) para provar
 * compatibilidade de fio - nao ha modulo Java compartilhado entre os dois deploys para uma
 * dependencia de compilacao (ver design.md, Risco de strings duplicadas).
 */
@SpringBootTest
@Testcontainers
@DisplayName("OutboxPublicadorScheduler + OutboxPublicadorService (fim a fim)")
class OutboxPublicadorConsumerIT {

    private static final String FILA_TESTE_OCUPACAO = "relatorios.ocupacao";
    private static final long TIMEOUT_POLL_MS = 5_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private TopicExchange dominioExchange;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);

        Queue filaTeste = new Queue(FILA_TESTE_OCUPACAO, true);
        rabbitAdmin.declareQueue(filaTeste);
        Binding binding =
                BindingBuilder.bind(filaTeste)
                        .to(dominioExchange)
                        .with(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY);
        rabbitAdmin.declareBinding(binding);
    }

    @AfterEach
    void tearDown() {
        drenarFila(FILA_TESTE_OCUPACAO);
        transactionTemplate.executeWithoutResult(status -> repository.deleteAll());
    }

    private void drenarFila(String fila) {
        while (rabbitTemplate.receive(fila, 200) != null) {
            // esvazia mensagem residual de um teste anterior, mesmo padrao de VagaLiberadaConsumerIT
            // em alocacreche-relatorios.
        }
    }

    @Test
    @DisplayName("evento pendente e publicado na fila correta e marcado PROCESSADO")
    void eventoValidoEhPublicadoEMarcadoProcessado() throws InterruptedException, IOException {
        Long id = inserirPendente("vaga.liberada", "{\"turmaId\":300}");

        Message mensagem = rabbitTemplate.receive(FILA_TESTE_OCUPACAO, TIMEOUT_POLL_MS);

        assertThat(mensagem).isNotNull();
        assertJsonEquivalente(mensagem.getBody(), "{\"turmaId\":300}");
        assertThat(buscarStatusComRetry(id, TIMEOUT_POLL_MS)).isEqualTo("PROCESSADO");
    }

    @Test
    @DisplayName("tipoEvento desconhecido vira ERRO sem impedir outro evento valido do mesmo poll")
    void tipoEventoDesconhecidoViraErroSemBloquearOutroEventoDoMesmoPoll()
            throws InterruptedException {
        Long idInvalido = inserirPendente("evento.inexistente", "{}");
        Long idValido = inserirPendente("vaga.liberada", "{\"turmaId\":302}");

        assertThat(buscarStatusComRetry(idValido, TIMEOUT_POLL_MS)).isEqualTo("PROCESSADO");
        assertThat(buscarStatus(idInvalido)).isEqualTo("ERRO");
    }

    @Test
    @DisplayName("eventos pendentes sao publicados em ordem de id ascendente")
    void eventosSaoPublicadosEmOrdemDeIdAscendente() throws InterruptedException, IOException {
        Long primeiro = inserirPendente("vaga.liberada", "{\"turmaId\":303}");
        Long segundo = inserirPendente("vaga.liberada", "{\"turmaId\":304}");

        assertThat(buscarStatusComRetry(segundo, TIMEOUT_POLL_MS)).isEqualTo("PROCESSADO");

        Message primeiraMensagem = rabbitTemplate.receive(FILA_TESTE_OCUPACAO, 500);
        Message segundaMensagem = rabbitTemplate.receive(FILA_TESTE_OCUPACAO, 500);

        assertThat(primeiraMensagem).isNotNull();
        assertThat(segundaMensagem).isNotNull();
        assertJsonEquivalente(primeiraMensagem.getBody(), "{\"turmaId\":303}");
        assertJsonEquivalente(segundaMensagem.getBody(), "{\"turmaId\":304}");
        assertThat(primeiro).isLessThan(segundo);
    }

    @Test
    @DisplayName("evento ja PROCESSADO nunca e republicado")
    void eventoJaProcessadoNuncaEhRepublicado() throws InterruptedException {
        Long id = inserirPendente("vaga.liberada", "{\"turmaId\":305}");
        assertThat(buscarStatusComRetry(id, TIMEOUT_POLL_MS)).isEqualTo("PROCESSADO");
        rabbitTemplate.receive(FILA_TESTE_OCUPACAO, 500);

        Thread.sleep(2_500);

        Message mensagemInesperada = rabbitTemplate.receive(FILA_TESTE_OCUPACAO, 500);
        assertThat(mensagemInesperada).isNull();
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

    /**
     * Compara estruturalmente, nao byte a byte - a coluna payload e jsonb, e o Postgres reformata
     * o texto ao armazenar (ex.: adiciona espaco apos ":"), entao o valor lido de volta nunca e
     * garantidamente identico ao literal inserido, so semanticamente equivalente.
     */
    private void assertJsonEquivalente(byte[] mensagemRecebida, String jsonEsperado) throws IOException {
        assertThat(OBJECT_MAPPER.readTree(new String(mensagemRecebida, StandardCharsets.UTF_8)))
                .isEqualTo(OBJECT_MAPPER.readTree(jsonEsperado));
    }
}
