package org.alocacreche.core.inscricao.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.alocacreche.core.inscricao.CalculadoraScorePrioridade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A classe CalculadoraScorePrioridade da Stack A nao e um componente Spring, e o InscricaoService
 * dela a exige por construtor. A configuracao testada aqui fornece o bean sem alterar a classe e
 * sai de cena se a Stack A passar a registra-la.
 */
@DisplayName("CalculadoraScorePrioridadeConfig")
class CalculadoraScorePrioridadeConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner();

    @Test
    @DisplayName("fornece o bean quando ninguem o registrou")
    void fornecaOBeanQuandoNinguemORegistrou() {
        contexto
                .withUserConfiguration(CalculadoraScorePrioridadeConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(CalculadoraScorePrioridade.class));
    }

    @Test
    @DisplayName("nao duplica o bean quando a Stack A passa a registra-lo")
    void naoDuplicaOBeanQuandoAStackARegistra() {
        contexto
                .withUserConfiguration(RegistroDaStackA.class, CalculadoraScorePrioridadeConfig.class)
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(CalculadoraScorePrioridade.class);
                            assertThat(ctx.getBean(CalculadoraScorePrioridade.class))
                                    .isSameAs(ctx.getBean(RegistroDaStackA.class).instancia);
                        });
    }

    @Configuration
    static class RegistroDaStackA {

        final CalculadoraScorePrioridade instancia = new CalculadoraScorePrioridade();

        @Bean
        CalculadoraScorePrioridade calculadoraDaStackA() {
            return instancia;
        }
    }
}
