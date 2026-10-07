package org.alocacreche.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Garante os valores de configuracao que o primeiro start em banco vazio exige. */
@DisplayName("application.yml do core")
class ConfiguracaoAplicacaoTest {

    @Test
    @DisplayName("ddl-auto padrao e update: banco vazio precisa criar as tabelas na primeira subida")
    void ddlAutoPadraoEUpdate() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties propriedades = yaml.getObject();

        assertThat(propriedades).isNotNull();
        assertThat(propriedades.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
    }
}
