package org.alocacreche.relatorios.ocupacao;

import java.time.LocalDateTime;

/**
 * Payload do evento VagaLiberada, publicado pela Outbox do monolito e consumido via RabbitMQ.
 * Contrato definido em docs/context/relatorios.md e .specs/features/relatorios-ocupacao-vaga-liberada/context.md.
 */
public record VagaLiberadaEvent(
        Long eventId,
        String tipoEvento,
        Long municipioId,
        Long turmaId,
        Long crecheId,
        Integer vagasDisponiveis,
        LocalDateTime ocorridoEm) {
}
