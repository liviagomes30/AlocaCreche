package org.alocacreche.relatorios.matricula.dto;

import java.time.LocalDate;

public record RelatorioConfirmacaoMatriculaDTO(
        Long municipioId,
        LocalDate periodo,
        Long totalMatriculas,
        Long totalConfirmadas,
        Double taxaConfirmacao) {
}
