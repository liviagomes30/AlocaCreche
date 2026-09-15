package org.alocacreche.relatorios.filaespera;

/**
 * Tipa o resultado agregado por turma da query nativa de FilaEsperaInscricaoRepository.buscarRelatorio,
 * sem carregar a entidade completa.
 */
public interface RelatorioFilaEsperaProjection {

    Long getTurmaId();

    Long getCrecheId();

    Long getMunicipioId();

    Long getQuantidadeAguardando();

    Double getTempoMedioEsperaDias();
}
