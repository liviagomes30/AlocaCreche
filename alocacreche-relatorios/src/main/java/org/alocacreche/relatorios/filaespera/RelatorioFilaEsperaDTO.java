package org.alocacreche.relatorios.filaespera;

public record RelatorioFilaEsperaDTO(
        Long turmaId,
        Long crecheId,
        Long municipioId,
        Long quantidadeAguardando,
        Double tempoMedioEsperaDias) {
}
