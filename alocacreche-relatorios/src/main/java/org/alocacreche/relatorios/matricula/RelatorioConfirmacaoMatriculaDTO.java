package org.alocacreche.relatorios.matricula;

import java.time.LocalDate;

public record RelatorioConfirmacaoMatriculaDTO(
        Long municipioId,
        LocalDate periodo,
        Long totalMatriculas,
        Long totalConfirmadas,
        Double taxaConfirmacao) {
}
