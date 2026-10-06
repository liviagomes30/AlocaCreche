package org.alocacreche.core.inscricao.config;

import org.alocacreche.core.inscricao.CalculadoraScorePrioridade;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CalculadoraScorePrioridadeConfig {

    // TODO: remover quando a Stack A registrar CalculadoraScorePrioridade como componente Spring
    @Bean
    @ConditionalOnMissingBean(CalculadoraScorePrioridade.class)
    public CalculadoraScorePrioridade calculadoraScorePrioridade() {
        return new CalculadoraScorePrioridade();
    }
}
