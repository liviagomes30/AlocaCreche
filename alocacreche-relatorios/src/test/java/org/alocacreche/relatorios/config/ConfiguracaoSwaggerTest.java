package org.alocacreche.relatorios.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Garante o padrao do Swagger no MSRelatorios: ligado em desenvolvimento, desligado no perfil prod
 * (a menos que a variavel o ligue de forma explicita), com as duas chaves do springdoc presas a
 * mesma propriedade {@code alocacreche.swagger.habilitado}.
 */
@DisplayName("application.yml do MSRelatorios: padrao do Swagger")
class ConfiguracaoSwaggerTest {

    @Test
    @DisplayName("Swagger vem ligado por padrao em desenvolvimento e as propriedades do springdoc seguem a mesma chave")
    void swaggerLigadoPorPadraoEmDesenvolvimento() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(contexto -> {
                    assertThat(contexto.getEnvironment().getProperty("alocacreche.swagger.habilitado"))
                            .isEqualTo("true");
                    assertThat(contexto.getEnvironment().getProperty("springdoc.api-docs.enabled"))
                            .isEqualTo("true");
                    assertThat(contexto.getEnvironment().getProperty("springdoc.swagger-ui.enabled"))
                            .isEqualTo("true");
                });
    }

    @Test
    @DisplayName("no perfil prod o Swagger fica desligado por padrao nas tres propriedades")
    void swaggerDesligadoPorPadraoNoPerfilProd() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod")
                .run(contexto -> {
                    assertThat(contexto.getEnvironment().getProperty("alocacreche.swagger.habilitado"))
                            .isEqualTo("false");
                    assertThat(contexto.getEnvironment().getProperty("springdoc.api-docs.enabled"))
                            .isEqualTo("false");
                    assertThat(contexto.getEnvironment().getProperty("springdoc.swagger-ui.enabled"))
                            .isEqualTo("false");
                });
    }

    @Test
    @DisplayName("no perfil prod a variavel ALOCACRECHE_SWAGGER_HABILITADO=true liga o Swagger de forma explicita")
    void variavelLigaOSwaggerNoPerfilProd() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod", "ALOCACRECHE_SWAGGER_HABILITADO=true")
                .run(contexto -> {
                    assertThat(contexto.getEnvironment().getProperty("alocacreche.swagger.habilitado"))
                            .isEqualTo("true");
                    assertThat(contexto.getEnvironment().getProperty("springdoc.api-docs.enabled"))
                            .isEqualTo("true");
                });
    }
}
