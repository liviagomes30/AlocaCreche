package org.alocacreche.relatorios.ocupacao.event;

import java.time.LocalDateTime;

public record VagaLiberadaEvent(
        Long eventId,
        String tipoEvento,
        Long municipioId,
        Long turmaId,
        Long crecheId,
        Integer vagasDisponiveis,
        LocalDateTime ocorridoEm) {
}
