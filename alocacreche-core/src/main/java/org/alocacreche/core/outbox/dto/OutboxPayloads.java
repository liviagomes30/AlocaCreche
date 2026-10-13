package org.alocacreche.core.outbox.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class OutboxPayloads {

    private OutboxPayloads() {
    }

    /**
     * {@code faixaEtaria}, {@code crecheNome} e {@code bairro} alimentam o painel publico do MSRelatorios (vagas por
     * faixa e lista de creches com vaga); sao informacao publica da creche e podem ser nulos.
     */
    public record VagaLiberadaPayload(
            String tipoEvento,
            Long municipioId,
            Long turmaId,
            Long crecheId,
            Integer vagasDisponiveis,
            String faixaEtaria,
            String crecheNome,
            String bairro,
            LocalDateTime ocorridoEm) {

        public VagaLiberadaPayload(
                String tipoEvento,
                Long municipioId,
                Long turmaId,
                Long crecheId,
                Integer vagasDisponiveis,
                LocalDateTime ocorridoEm) {
            this(tipoEvento, municipioId, turmaId, crecheId, vagasDisponiveis, null, null, null, ocorridoEm);
        }
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
