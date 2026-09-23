package org.alocacreche.relatorios.matricula;

import java.time.LocalDate;

/**
 * Forma de resposta do relatorio de taxa de confirmacao de matricula. taxaConfirmacao e nulo (nao
 * zero) quando nao ha nenhuma matricula no periodo - divisao por zero seria uma resposta enganosa
 * (0% de confirmacao é diferente de "sem dados").
 */
public record RelatorioConfirmacaoMatriculaDTO(
        Long municipioId,
        LocalDate periodo,
        Long totalMatriculas,
        Long totalConfirmadas,
        Double taxaConfirmacao) {
}
