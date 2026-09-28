package org.alocacreche.relatorios.filaespera;

public interface RelatorioFilaEsperaProjection {

    Long getTurmaId();

    Long getCrecheId();

    Long getMunicipioId();

    Long getQuantidadeAguardando();

    Double getTempoMedioEsperaDias();
}
