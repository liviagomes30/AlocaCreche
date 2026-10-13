package org.alocacreche.relatorios.filaespera.projection;

/** Totais da fila de um municipio: quantos aguardam, quantas inscricoes ja passaram por ela e a espera media em dias. */
public interface ResumoFilaProjection {

    Long getAguardando();

    Long getTotal();

    Double getEsperaMediaDias();
}
