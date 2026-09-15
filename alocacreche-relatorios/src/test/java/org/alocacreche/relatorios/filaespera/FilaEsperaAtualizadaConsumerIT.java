package org.alocacreche.relatorios.filaespera;

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
 * Ponta a ponta com RabbitMQ e Postgres reais (Testcontainers). Cobre FILA-01/04/05 (caminho
 * feliz), FILA-07 (payload malformado direto pra DLQ), FILA-08 (dataSaida anterior a dataEntrada
 * direto pra DLQ) e FILA-09 (falha transitoria - 3 tentativas, depois DLQ).
 *
 * <p>Malformado (FILA-07) e dataSaida invalida (FILA-08) sao distinguidos pela contagem de
 * invocacao do listener, nao so pela chegada na DLQ (ambas as condicoes sao verdade com 1 ou 3
 * tentativas - ver .specs/STATE.md AD-001, licoes L-002/L-005): JSON malformado falha na
 * conversao da mensagem, antes do metodo do listener ser invocado (0 invocacoes); dataSaida
 * anterior a dataEntrada e detectada dentro de {@code receber()} (1 invocacao, excecao lancada
 * depois de entrar no metodo).
 */
@SpringBootTest
@Testcontainers
class FilaEsperaAtualizadaConsumerIT {

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

    @MockitoSpyBean private FilaEsperaInscricaoRepository repository;

    @MockitoSpyBean private FilaEsperaAtualizadaListener listener;

    @Autowired private PlatformTransactionManager transactionManager;

    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            TenantContext.aplicar(entityManager, 1L);
                            entityManager
                                    .createNativeQuery("DELETE FROM fila_espera_inscricao")
                                    .executeUpdate();
                        });
        drenarFila(RabbitMqConfig.FILA_ESPERA_QUEUE);
        drenarFila(RabbitMqConfig.FILA_ESPERA_DLQ);
    }

    private void drenarFila(String fila) {
        while (rabbitTemplate.receive(fila, 200) != null) {
            // esvazia qualquer mensagem residual de um teste anterior - um unico receive()
            // deixaria lixo se algum teste vier a publicar mais de uma mensagem por fila.
        }
    }

    @Test
    void eventoValidoAtualizaProjecao() throws InterruptedException {
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L,
                        "FilaEsperaAtualizada",
                        1L,
                        300L,
                        30L,
                        900L,
                        LocalDate.now().minusDays(3),
                        null,
                        LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.FILA_ATUALIZADA_ROUTING_KEY, evento);

        FilaEsperaInscricao inscricao = buscarComRetry(900L, 1L, 5_000);
        assertThat(inscricao).isNotNull();
        assertThat(inscricao.getTurmaId()).isEqualTo(300L);
        assertThat(inscricao.getCrecheId()).isEqualTo(30L);
        assertThat(inscricao.getDataSaida()).isNull();
        assertThat(inscricao.getUltimaSequenciaAplicada()).isEqualTo(1L);
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
                RabbitMqConfig.FILA_ATUALIZADA_ROUTING_KEY,
                mensagemInvalida);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.FILA_ESPERA_DLQ, 5_000);
        assertThat(naDlq).isNotNull();

        Message naFilaPrincipal = rabbitTemplate.receive(RabbitMqConfig.FILA_ESPERA_QUEUE, 500);
        assertThat(naFilaPrincipal).isNull();
        // A excecao de conversao acontece antes do metodo do listener ser chamado - 0 invocacoes e
        // o sinal correto para este caso (distinto do caso de dataSaida invalida abaixo, onde o
        // listener e invocado 1 vez e a excecao acontece dentro de receber()).
        verify(listener, never()).receber(any());
    }

    @Test
    void dataSaidaAnteriorADataEntradaVaiDiretoParaDlqComUmaUnicaInvocacaoDoListener() {
        Long inscricaoId = 901L;
        LocalDate dataEntrada = LocalDate.now();
        LocalDate dataSaidaInvalida = dataEntrada.minusDays(1);
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L,
                        "FilaEsperaAtualizada",
                        1L,
                        310L,
                        31L,
                        inscricaoId,
                        dataEntrada,
                        dataSaidaInvalida,
                        LocalDateTime.now());

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.FILA_ATUALIZADA_ROUTING_KEY, evento);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.FILA_ESPERA_DLQ, 5_000);
        assertThat(naDlq).isNotNull();
        verify(repository, never())
                .upsert(eq(inscricaoId), any(), any(), any(), any(), any(), any());
        // Sinal decisivo: "repositorio nunca chamado" tambem seria verdade com 3 tentativas antes
        // da DLQ (a excecao acontece antes do repositorio ser tocado em cada tentativa). So a
        // contagem de invocacoes do listener distingue rejeicao imediata (1 chamada) de
        // retry-entao-DLQ (3 chamadas) - ver .specs/STATE.md AD-001.
        verify(listener, times(1)).receber(argThat(e -> inscricaoId.equals(e.inscricaoId())));
    }

    @Test
    void falhaTransitoriaVaiParaDlqApenasAposTresTentativas() {
        Long inscricaoId = 902L;
        doThrow(new RuntimeException("falha transitoria simulada"))
                .when(repository)
                .upsert(eq(inscricaoId), any(), any(), any(), any(), any(), any());

        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L,
                        "FilaEsperaAtualizada",
                        1L,
                        320L,
                        32L,
                        inscricaoId,
                        LocalDate.now().minusDays(1),
                        null,
                        LocalDateTime.now());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DOMINIO_EXCHANGE, RabbitMqConfig.FILA_ATUALIZADA_ROUTING_KEY, evento);

        Message naDlq = rabbitTemplate.receive(RabbitMqConfig.FILA_ESPERA_DLQ, 5_000);
        assertThat(naDlq).isNotNull();
        verify(repository, times(3))
                .upsert(eq(inscricaoId), any(), any(), any(), any(), any(), any());
    }

    private FilaEsperaInscricao buscarComRetry(Long inscricaoId, Long municipioId, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            FilaEsperaInscricao inscricao =
                    new TransactionTemplate(transactionManager)
                            .execute(
                                    status -> {
                                        TenantContext.aplicar(entityManager, municipioId);
                                        return entityManager.find(FilaEsperaInscricao.class, inscricaoId);
                                    });
            if (inscricao != null) {
                return inscricao;
            }
            Thread.sleep(200);
        }
        return null;
    }
}
