package org.alocacreche.relatorios.matricula.event;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.alocacreche.relatorios.matricula.domain.StatusMatricula;

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
