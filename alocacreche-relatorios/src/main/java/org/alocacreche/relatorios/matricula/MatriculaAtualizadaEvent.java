package org.alocacreche.relatorios.matricula;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MatriculaAtualizadaEvent(
        Long eventId,
        String tipoEvento,
        Long municipioId,
        Long turmaId,
        Long crecheId,
        Long inscricaoId,
        Long matriculaId,
        StatusMatricula status,
        Double distanciaKm,
        LocalDate dataConvocacao,
        LocalDate dataConfirmacao,
        LocalDateTime ocorridoEm) {
}
