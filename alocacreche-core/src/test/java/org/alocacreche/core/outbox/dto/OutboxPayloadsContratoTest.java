package org.alocacreche.core.outbox.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.alocacreche.core.outbox.dto.OutboxPayloads.FilaEsperaAtualizadaPayload;
import org.alocacreche.core.outbox.dto.OutboxPayloads.MatriculaAtualizadaPayload;
import org.alocacreche.core.outbox.dto.OutboxPayloads.VagaLiberadaPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Fixa o contrato dos tres eventos consumidos pelo MSRelatorios (PROD-02, PROD-09, PROD-12,
 * PROD-19): nomes e tipos exatos dos campos, datas em ISO-8601, nulos aceitos e ausencia de
 * {@code eventId}, que o Publicador injeta a partir do id da Outbox.
 */
@DisplayName("Contrato dos payloads de evento do core com o MSRelatorios")
class OutboxPayloadsContratoTest {

    private static final LocalDateTime OCORRIDO_EM = LocalDateTime.of(2026, 10, 5, 14, 30, 15);

    private final ObjectMapper objectMapper =
            Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    @Test
    @DisplayName("VagaLiberada serializa exatamente os campos do consumidor, sem eventId")
    void vagaLiberadaTemExatamenteOsCamposDoConsumidor() throws Exception {
        VagaLiberadaPayload payload =
                new VagaLiberadaPayload("vaga.liberada", 1L, 2L, 3L, 5, OCORRIDO_EM);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(payload));

        assertThat(nomesDosCampos(json))
                .containsExactlyInAnyOrder(
                        "tipoEvento", "municipioId", "turmaId", "crecheId", "vagasDisponiveis", "ocorridoEm");
        assertThat(json.get("tipoEvento").asText()).isEqualTo("vaga.liberada");
        assertThat(json.get("municipioId").isIntegralNumber()).isTrue();
        assertThat(json.get("turmaId").asLong()).isEqualTo(2L);
        assertThat(json.get("crecheId").asLong()).isEqualTo(3L);
        assertThat(json.get("vagasDisponiveis").isIntegralNumber()).isTrue();
        assertThat(json.get("vagasDisponiveis").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("FilaEsperaAtualizada serializa exatamente os campos do consumidor, sem eventId")
    void filaEsperaAtualizadaTemExatamenteOsCamposDoConsumidor() throws Exception {
        FilaEsperaAtualizadaPayload payload = filaPayload();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(payload));

        assertThat(nomesDosCampos(json))
                .containsExactlyInAnyOrder(
                        "tipoEvento",
                        "municipioId",
                        "turmaId",
                        "crecheId",
                        "inscricaoId",
                        "dataEntrada",
                        "dataSaida",
                        "ocorridoEm");
        assertThat(json.get("tipoEvento").asText()).isEqualTo("fila.atualizada");
        assertThat(json.get("inscricaoId").asLong()).isEqualTo(4L);
    }

    @Test
    @DisplayName("MatriculaAtualizada serializa exatamente os campos do consumidor, sem eventId")
    void matriculaAtualizadaTemExatamenteOsCamposDoConsumidor() throws Exception {
        MatriculaAtualizadaPayload payload = matriculaPayload("PENDENTE", 2.75, null);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(payload));

        assertThat(nomesDosCampos(json))
                .containsExactlyInAnyOrder(
                        "tipoEvento",
                        "municipioId",
                        "turmaId",
                        "crecheId",
                        "inscricaoId",
                        "matriculaId",
                        "status",
                        "distanciaKm",
                        "dataConvocacao",
                        "dataConfirmacao",
                        "ocorridoEm");
        assertThat(json.get("matriculaId").asLong()).isEqualTo(6L);
        assertThat(json.get("status").asText()).isEqualTo("PENDENTE");
        assertThat(json.get("distanciaKm").isFloatingPointNumber()).isTrue();
        assertThat(json.get("distanciaKm").asDouble()).isEqualTo(2.75);
    }

    @Test
    @DisplayName("datas e data-hora saem em ISO-8601 como texto, nao como numero")
    void datasSaemEmIso8601() throws Exception {
        MatriculaAtualizadaPayload payload =
                matriculaPayload("CONFIRMADA", 1.5, LocalDate.of(2026, 10, 7));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(payload));

        assertThat(json.get("dataConvocacao").isTextual()).isTrue();
        assertThat(json.get("dataConvocacao").asText()).isEqualTo("2026-10-05");
        assertThat(json.get("dataConfirmacao").asText()).isEqualTo("2026-10-07");
        assertThat(json.get("ocorridoEm").isTextual()).isTrue();
        assertThat(json.get("ocorridoEm").asText()).isEqualTo("2026-10-05T14:30:15");
    }

    @Test
    @DisplayName("dataEntrada da fila sai em ISO-8601 como texto, nao como numero")
    void dataEntradaSaiEmIso8601() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(filaPayload()));

        assertThat(json.get("dataEntrada").isTextual()).isTrue();
        assertThat(json.get("dataEntrada").asText()).isEqualTo("2026-10-05");
    }

    @Test
    @DisplayName("dataSaida e dataConfirmacao aceitam nulo e saem como null no JSON")
    void camposOpcionaisAceitamNulo() throws Exception {
        JsonNode jsonFila = objectMapper.readTree(objectMapper.writeValueAsString(filaPayload()));
        JsonNode jsonMatricula =
                objectMapper.readTree(
                        objectMapper.writeValueAsString(matriculaPayload("PENDENTE", 1.0, null)));

        assertThat(jsonFila.get("dataSaida").isNull()).isTrue();
        assertThat(jsonMatricula.get("dataConfirmacao").isNull()).isTrue();
    }

    @Test
    @DisplayName("o JSON gerado desserializa de volta nos mesmos campos e valores")
    void jsonGeradoDesserializaNosCamposEsperados() throws Exception {
        MatriculaAtualizadaPayload original =
                matriculaPayload("CONFIRMADA", 3.25, LocalDate.of(2026, 10, 7));

        String json = objectMapper.writeValueAsString(original);
        MatriculaAtualizadaPayload lido = objectMapper.readValue(json, MatriculaAtualizadaPayload.class);

        assertThat(lido).isEqualTo(original);
    }

    @Test
    @DisplayName("nenhum payload carrega eventId: o Publicador o injeta a partir da Outbox")
    void nenhumPayloadTemEventId() throws Exception {
        String vaga =
                objectMapper.writeValueAsString(
                        new VagaLiberadaPayload("vaga.liberada", 1L, 2L, 3L, 5, OCORRIDO_EM));
        String fila = objectMapper.writeValueAsString(filaPayload());
        String matricula =
                objectMapper.writeValueAsString(matriculaPayload("PENDENTE", 1.0, null));

        assertThat(vaga).doesNotContain("eventId");
        assertThat(fila).doesNotContain("eventId");
        assertThat(matricula).doesNotContain("eventId");
    }

    @Test
    @DisplayName("VagaLiberada serializada e igual ao fixture compartilhado com o MSRelatorios")
    void vagaLiberadaIgualAoFixture() throws Exception {
        VagaLiberadaPayload payload =
                new VagaLiberadaPayload("vaga.liberada", 1L, 2L, 3L, 5, OCORRIDO_EM);

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(payload)))
                .isEqualTo(objectMapper.readTree(EventosContratoFixtures.VAGA_LIBERADA));
    }

    @Test
    @DisplayName("FilaEsperaAtualizada serializada e igual ao fixture, com dataEntrada ISO e dataSaida nula")
    void filaEsperaAtualizadaIgualAoFixture() throws Exception {
        FilaEsperaAtualizadaPayload payload =
                new FilaEsperaAtualizadaPayload(
                        "fila.atualizada", 1L, 2L, 3L, 4L, LocalDate.of(2026, 10, 3), null, OCORRIDO_EM);

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(payload)))
                .isEqualTo(objectMapper.readTree(EventosContratoFixtures.FILA_ATUALIZADA));
    }

    @Test
    @DisplayName("MatriculaAtualizada serializada e igual ao fixture, com as datas em ISO-8601")
    void matriculaAtualizadaIgualAoFixture() throws Exception {
        MatriculaAtualizadaPayload payload =
                new MatriculaAtualizadaPayload(
                        "matricula.atualizada",
                        1L,
                        2L,
                        3L,
                        4L,
                        6L,
                        "CONFIRMADA",
                        2.75,
                        LocalDate.of(2026, 10, 5),
                        LocalDate.of(2026, 10, 7),
                        OCORRIDO_EM);

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(payload)))
                .isEqualTo(objectMapper.readTree(EventosContratoFixtures.MATRICULA_ATUALIZADA));
    }

    private static FilaEsperaAtualizadaPayload filaPayload() {
        return new FilaEsperaAtualizadaPayload(
                "fila.atualizada", 1L, 2L, 3L, 4L, LocalDate.of(2026, 10, 5), null, OCORRIDO_EM);
    }

    private static MatriculaAtualizadaPayload matriculaPayload(
            String status, Double distanciaKm, LocalDate dataConfirmacao) {
        return new MatriculaAtualizadaPayload(
                "matricula.atualizada",
                1L,
                2L,
                3L,
                4L,
                6L,
                status,
                distanciaKm,
                LocalDate.of(2026, 10, 5),
                dataConfirmacao,
                OCORRIDO_EM);
    }

    private static List<String> nomesDosCampos(JsonNode json) {
        List<String> nomes = new ArrayList<>();
        json.fieldNames().forEachRemaining(nomes::add);
        return nomes;
    }
}
