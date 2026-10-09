package org.alocacreche.core.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.alocacreche.core.outbox.dto.OutboxPayloads.FilaEsperaAtualizadaPayload;
import org.alocacreche.core.outbox.dto.OutboxPayloads.VagaLiberadaPayload;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova que o {@code OutboxEventWriter} grava o evento atomicamente com a transacao do chamador
 * (PROD-07, PROD-12) contra um Postgres real: grava PENDENTE dentro de transacao, recusa gravar
 * fora dela, desfaz junto com o rollback e rejeita routing keys desconhecidas.
 */
@SpringBootTest
@Testcontainers
@DisplayName("OutboxEventWriter: gravacao atomica com a transacao do chamador")
class OutboxEventWriterIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private OutboxEventWriter writer;
    @Autowired private OutboxEventRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    private TransactionTemplate transacao;

    @BeforeEach
    void limparOutbox() {
        transacao = new TransactionTemplate(transactionManager);
        repository.deleteAll();
    }

    @Test
    @DisplayName("dentro de uma transacao grava a linha PENDENTE com tipoEvento igual a routing key e o JSON do payload")
    void dentroDeTransacaoGravaLinhaPendente() throws Exception {
        VagaLiberadaPayload payload =
                new VagaLiberadaPayload(
                        "vaga.liberada", 1L, 2L, 3L, 5, LocalDateTime.of(2026, 10, 5, 14, 30, 15));

        transacao.executeWithoutResult(
                status -> writer.registrar(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY, payload));

        List<OutboxEvent> eventos = repository.findAll();
        assertThat(eventos).hasSize(1);
        OutboxEvent evento = eventos.get(0);
        assertThat(evento.getTipoEvento()).isEqualTo("vaga.liberada");
        assertThat(evento.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDENTE);
        JsonNode json = objectMapper.readTree(evento.getPayload());
        assertThat(json.get("tipoEvento").asText()).isEqualTo("vaga.liberada");
        assertThat(json.get("municipioId").asLong()).isEqualTo(1L);
        assertThat(json.get("turmaId").asLong()).isEqualTo(2L);
        assertThat(json.get("crecheId").asLong()).isEqualTo(3L);
        assertThat(json.get("vagasDisponiveis").asInt()).isEqualTo(5);
        assertThat(json.get("ocorridoEm").asText()).isEqualTo("2026-10-05T14:30:15");
    }

    @Test
    @DisplayName("o ObjectMapper do Spring grava LocalDate em ISO-8601 e dataSaida nula como null")
    void objectMapperDoSpringGravaDatasEmIsoENulo() throws Exception {
        FilaEsperaAtualizadaPayload payload =
                new FilaEsperaAtualizadaPayload(
                        "fila.atualizada",
                        1L,
                        2L,
                        3L,
                        4L,
                        LocalDate.of(2026, 10, 5),
                        null,
                        LocalDateTime.of(2026, 10, 5, 14, 30, 15));

        transacao.executeWithoutResult(
                status -> writer.registrar(OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY, payload));

        JsonNode json = objectMapper.readTree(repository.findAll().get(0).getPayload());
        assertThat(json.get("dataEntrada").asText()).isEqualTo("2026-10-05");
        assertThat(json.get("dataSaida").isNull()).isTrue();
    }

    @Test
    @DisplayName("fora de transacao lanca excecao e nao grava nada")
    void foraDeTransacaoLancaExcecaoENaoGrava() {
        VagaLiberadaPayload payload =
                new VagaLiberadaPayload("vaga.liberada", 1L, 2L, 3L, 5, LocalDateTime.now());

        assertThatThrownBy(() -> writer.registrar(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY, payload))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("se a transacao do chamador desfaz, a linha da Outbox desaparece junto")
    void rollbackDoChamadorDesfazALinha() {
        VagaLiberadaPayload payload =
                new VagaLiberadaPayload("vaga.liberada", 1L, 2L, 3L, 5, LocalDateTime.now());

        assertThatThrownBy(
                        () ->
                                transacao.executeWithoutResult(
                                        status -> {
                                            writer.registrar(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY, payload);
                                            throw new IllegalStateException("falha apos gravar o evento");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("routing key fora das tres conhecidas e rejeitada e nada e gravado")
    void routingKeyDesconhecidaERejeitada() {
        VagaLiberadaPayload payload =
                new VagaLiberadaPayload("qualquer.coisa", 1L, 2L, 3L, 5, LocalDateTime.now());

        assertThatThrownBy(
                        () ->
                                transacao.executeWithoutResult(
                                        status -> writer.registrar("qualquer.coisa", payload)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.count()).isZero();
    }
}
