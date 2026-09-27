package org.alocacreche.core.notificacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConfiguracaoPriorizacao")
class ConfiguracaoPriorizacaoTest {

    @Nested
    @DisplayName("quando os pesos sao validos")
    class QuandoPesosValidos {

        @Test
        @DisplayName("nao lanca excecao quando os 4 pesos somam 1.0")
        void naoLancaExcecaoQuandoPesosSomamUm() {
            ConfiguracaoPriorizacao configuracao = configuracaoCom(0.4, 0.3, 0.2, 0.1);

            assertThat(configuracao.getPesoRenda()).isEqualTo(0.4);
            configuracao.validar();
        }

        @Test
        @DisplayName("aceita soma 1.0 dentro da tolerancia de ponto flutuante")
        void aceitaSomaDentroDaTolerancia() {
            ConfiguracaoPriorizacao configuracao = configuracaoCom(0.25, 0.25, 0.25, 0.2501);

            configuracao.validar();
        }
    }

    @Nested
    @DisplayName("quando algum peso esta ausente")
    class QuandoPesoAusente {

        @Test
        @DisplayName("lanca IllegalStateException quando um peso e nulo")
        void lancaExcecaoQuandoPesoNulo() {
            ConfiguracaoPriorizacao configuracao =
                    ConfiguracaoPriorizacao.builder()
                            .pesoRenda(0.4)
                            .pesoVulnerabilidade(0.3)
                            .pesoDistancia(0.3)
                            .pesoIrmaoMatriculado(null)
                            .build();

            assertThatThrownBy(configuracao::validar)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("todos os pesos preenchidos");
        }
    }

    @Nested
    @DisplayName("quando algum peso e negativo")
    class QuandoPesoNegativo {

        @Test
        @DisplayName("lanca IllegalStateException quando um peso e negativo, mesmo somando 1.0")
        void lancaExcecaoQuandoPesoNegativo() {
            ConfiguracaoPriorizacao configuracao = configuracaoCom(1.2, -0.2, 0.0, 0.0);

            assertThatThrownBy(configuracao::validar)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nao podem ser negativos");
        }
    }

    @Nested
    @DisplayName("quando a soma dos pesos nao e 1.0")
    class QuandoSomaInvalida {

        @Test
        @DisplayName("lanca IllegalStateException quando a soma fica abaixo de 1.0")
        void lancaExcecaoQuandoSomaMenorQueUm() {
            ConfiguracaoPriorizacao configuracao = configuracaoCom(0.2, 0.2, 0.2, 0.1);

            assertThatThrownBy(configuracao::validar)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Soma dos pesos");
        }

        @Test
        @DisplayName("lanca IllegalStateException quando a soma fica acima de 1.0")
        void lancaExcecaoQuandoSomaMaiorQueUm() {
            ConfiguracaoPriorizacao configuracao = configuracaoCom(0.5, 0.5, 0.5, 0.5);

            assertThatThrownBy(configuracao::validar)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Soma dos pesos");
        }
    }

    private static ConfiguracaoPriorizacao configuracaoCom(
            double pesoRenda, double pesoVulnerabilidade, double pesoDistancia, double pesoIrmaoMatriculado) {
        return ConfiguracaoPriorizacao.builder()
                .pesoRenda(pesoRenda)
                .pesoVulnerabilidade(pesoVulnerabilidade)
                .pesoDistancia(pesoDistancia)
                .pesoIrmaoMatriculado(pesoIrmaoMatriculado)
                .build();
    }
}
