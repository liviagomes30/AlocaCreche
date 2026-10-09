package org.alocacreche.relatorios.filaespera.event;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record FilaEsperaAtualizadaEvent(
        Long eventId,
        String tipoEvento,
        Long municipioId,
        Long turmaId,
        Long crecheId,
        Long inscricaoId,
        LocalDate dataEntrada,
        LocalDate dataSaida,
        LocalDateTime ocorridoEm) {
}
