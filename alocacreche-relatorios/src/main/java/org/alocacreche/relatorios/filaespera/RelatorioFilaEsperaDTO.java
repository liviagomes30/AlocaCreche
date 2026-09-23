package org.alocacreche.relatorios.filaespera;

/**
 * Forma de resposta da consulta de fila de espera. Nunca expõe {@link FilaEsperaInscricao}
 * (entidade JPA) nem {@link RelatorioFilaEsperaProjection} (projeção interna) diretamente pela API
 * (FILA-13).
 */
public record RelatorioFilaEsperaDTO(
        Long turmaId,
        Long crecheId,
        Long municipioId,
        Long quantidadeAguardando,
        Double tempoMedioEsperaDias) {
}
