package org.alocacreche.core.inscricao;

import java.math.BigDecimal;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;

public class CalculadoraScorePrioridade {

    public double calcular(
            BigDecimal rendaPerCapita,
            boolean vulnerabilidade,
            boolean irmaoMatriculado,
            double fatorDistancia,
            ConfiguracaoPriorizacao configuracao) {

        double fatorRenda = calcularFatorRenda(rendaPerCapita);

        double fatorVulnerabilidade = vulnerabilidade ? 1.0 : 0.0;

        double fatorIrmaoMatriculado = irmaoMatriculado ? 1.0 : 0.0;

        return fatorRenda * configuracao.getPesoRenda()
                + fatorVulnerabilidade * configuracao.getPesoVulnerabilidade()
                + fatorDistancia * configuracao.getPesoDistancia()
                + fatorIrmaoMatriculado * configuracao.getPesoIrmaoMatriculado();
    }

    private double calcularFatorRenda(BigDecimal rendaPerCapita) {

        if (rendaPerCapita == null) {
            return 0.0;
        }

        if (rendaPerCapita.compareTo(new BigDecimal("500.00")) <= 0) {
            return 1.0;
        }

        if (rendaPerCapita.compareTo(new BigDecimal("1000.00")) <= 0) {
            return 0.75;
        }

        if (rendaPerCapita.compareTo(new BigDecimal("1500.00")) <= 0) {
            return 0.50;
        }

        if (rendaPerCapita.compareTo(new BigDecimal("2000.00")) <= 0) {
            return 0.25;
        }

        return 0.0;
    }
}