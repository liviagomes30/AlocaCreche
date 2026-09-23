package org.alocacreche.relatorios.matricula;

/**
 * Tipa o resultado agregado da query nativa de MatriculaRelatorioRepository.calcularTaxaConfirmacao,
 * sem carregar a entidade completa.
 */
public interface RelatorioConfirmacaoProjection {

    Long getTotalMatriculas();

    Long getTotalConfirmadas();
}
