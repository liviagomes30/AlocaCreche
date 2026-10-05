package org.alocacreche.core.outbox;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class OutboxPayloads {

    private OutboxPayloads() {
    }

    public record VagaLiberadaPayload(
            String tipoEvento,
            Long municipioId,
            Long turmaId,
            Long crecheId,
            Integer vagasDisponiveis,
            LocalDateTime ocorridoEm) {
    }

    public record FilaEsperaAtualizadaPayload(
            String tipoEvento,
            Long municipioId,
            Long turmaId,
            Long crecheId,
            Long inscricaoId,
            LocalDate dataEntrada,
            LocalDate dataSaida,
            LocalDateTime ocorridoEm) {
    }

    public record MatriculaAtualizadaPayload(
            String tipoEvento,
            Long municipioId,
            Long turmaId,
            Long crecheId,
            Long inscricaoId,
            Long matriculaId,
            String status,
            Double distanciaKm,
            LocalDate dataConvocacao,
            LocalDate dataConfirmacao,
            LocalDateTime ocorridoEm) {
    }
}
