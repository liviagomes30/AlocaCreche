package org.alocacreche.core.inscricao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.junit.jupiter.api.Test;

class CalculadoraScorePrioridadeTest {

    @Test
    void deveRetornarScoreMaximoParaCenarioDeMaiorPrioridade() {

        ConfiguracaoPriorizacao configuracao =
                ConfiguracaoPriorizacao.builder()
                        .pesoRenda(0.50)
                        .pesoVulnerabilidade(0.30)
                        .pesoDistancia(0.00)
                        .pesoIrmaoMatriculado(0.20)
                        .build();

        CalculadoraScorePrioridade calculadora =
                new CalculadoraScorePrioridade();

        double score = calculadora.calcular(
                new BigDecimal("500.00"),
                true,
                true,
                0.0,
                configuracao);

        assertEquals(1.0, score, 0.0001);
    }
}