package org.alocacreche.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
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
@SpringBootTest(properties = "alocacreche.outbox.publicador.habilitado=true")
@Testcontainers
@DisplayName("OutboxPublicadorScheduler + OutboxPublicadorService (fim a fim)")
class OutboxPublicadorConsumerIT {

    private static final String FILA_TESTE_OCUPACAO = "relatorios.ocupacao";
    private static final String FILA_TESTE_FILA_ATUALIZADA = "relatorios.fila.teste";
    private static final long ESPERA_SEM_FILA_MS = 3_000;
    /**
     * 2x o intervalo do poll (fixedDelay=1000ms) - margem para jitter de agendamento sob teste,
     * mas ainda apertado o suficiente para reprovar se o poll real ficar muito mais lento que o
     * ~1s prometido na spec (OUTPUB-01). Um timeout muito mais largo (ex.: 5s) provaria só entrega
     * eventual, nao o limite de latencia da spec.
     */
    private static final long TIMEOUT_POLL_MS = 2_000;
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
    @Autowired private OutboxPurgaService purgaService;
    @Autowired private ScheduledAnnotationBeanPostProcessor agendador;

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
        rabbitAdmin.deleteQueue(FILA_TESTE_FILA_ATUALIZADA);
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
        assertJsonEquivalente(mensagem.getBody(), "{\"turmaId\":300,\"eventId\":" + id + "}");
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
        assertJsonEquivalente(
                primeiraMensagem.getBody(), "{\"turmaId\":303,\"eventId\":" + primeiro + "}");
        assertJsonEquivalente(
                segundaMensagem.getBody(), "{\"turmaId\":304,\"eventId\":" + segundo + "}");
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

    @Test
    @DisplayName("sem fila ligada a routing key o evento permanece PENDENTE por mais de dois ciclos do poll")
    void semFilaLigadaEventoPermanecePendente() throws InterruptedException {
        Long id = inserirPendente("matricula.atualizada", "{\"turmaId\":307}");

        Thread.sleep(ESPERA_SEM_FILA_MS);

        assertThat(buscarStatus(id)).isEqualTo("PENDENTE");
    }

    @Test
    @DisplayName("evento que esperava sem fila e entregue uma unica vez quando a fila e ligada")
    void eventoQueEsperavaEhEntregueUmaVezQuandoFilaELigada()
            throws InterruptedException, IOException {
        Long id = inserirPendente("fila.atualizada", "{\"turmaId\":306}");

        Thread.sleep(ESPERA_SEM_FILA_MS);

        assertThat(buscarStatus(id)).isEqualTo("PENDENTE");

        Queue filaTeste = new Queue(FILA_TESTE_FILA_ATUALIZADA, true);
        rabbitAdmin.declareQueue(filaTeste);
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(filaTeste)
                        .to(dominioExchange)
                        .with(OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY));

        assertThat(buscarStatusComRetry(id, 5_000)).isEqualTo("PROCESSADO");
        Message mensagem = rabbitTemplate.receive(FILA_TESTE_FILA_ATUALIZADA, 1_000);
        assertThat(mensagem).isNotNull();
        assertJsonEquivalente(mensagem.getBody(), "{\"turmaId\":306,\"eventId\":" + id + "}");
        Thread.sleep(2_500);
        assertThat(rabbitTemplate.receive(FILA_TESTE_FILA_ATUALIZADA, 500)).isNull();
    }

    @Test
    @DisplayName("purga apaga so PROCESSADO antigo e mantem PROCESSADO recente e ERRO")
    void purgaApagaSoProcessadoAntigo() {
        LocalDateTime antigo = LocalDateTime.now().minusDays(10);
        Long processadoAntigo = inserirComStatus("PROCESSADO", antigo);
        Long processadoRecente = inserirComStatus("PROCESSADO", LocalDateTime.now().minusDays(1));
        Long erroAntigo = inserirComStatus("ERRO", antigo);

        int apagados = purgaService.purgarProcessadosAntigos();

        assertThat(apagados).isEqualTo(1);
        assertThat(existe(processadoAntigo)).isFalse();
        assertThat(existe(processadoRecente)).isTrue();
        assertThat(existe(erroAntigo)).isTrue();
    }

    @Test
    @DisplayName("a purga esta registrada como tarefa cron do agendador (1x por dia, 03:00)")
    void purgaEstaRegistradaComoTarefaCron() {
        List<String> expressoes =
                agendador.getScheduledTasks().stream()
                        .map(ScheduledTask::getTask)
                        .filter(CronTask.class::isInstance)
                        .map(CronTask.class::cast)
                        .filter(
                                tarefa ->
                                        tarefa.getRunnable()
                                                .toString()
                                                .contains("OutboxPurgaScheduler"))
                        .map(CronTask::getExpression)
                        .toList();

        assertThat(expressoes).containsExactly("0 0 3 * * *");
    }

    private Long inserirComStatus(String status, LocalDateTime criadoEm) {
        return transactionTemplate.execute(
                txStatus -> {
                    OutboxEvent evento = new OutboxEvent("vaga.liberada", "{}");
                    evento.setStatus(status);
                    evento.setCriadoEm(criadoEm);
                    return repository.save(evento).getId();
                });
    }

    private boolean existe(Long id) {
        return Boolean.TRUE.equals(
                transactionTemplate.execute(txStatus -> repository.existsById(id)));
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
