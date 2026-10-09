package org.alocacreche.core.comum.seguranca.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Garante que a representacao textual das propriedades do usuario inicial nunca expoe a senha nem
 * o email configurados.
 */
@DisplayName("UsuarioInicialProperties")
class UsuarioInicialPropertiesTest {

    private static final String EMAIL = "admin@prefeitura.gov.br";
    private static final String SENHA = "senha-super-secreta";

    @Test
    @DisplayName("toString nao expoe a senha")
    void toStringNaoExpoeSenha() {
        String texto = new UsuarioInicialProperties(EMAIL, SENHA, 3L).toString();

        assertThat(texto).doesNotContain(SENHA);
    }

    @Test
    @DisplayName("toString nao expoe o email")
    void toStringNaoExpoeEmail() {
        String texto = new UsuarioInicialProperties(EMAIL, SENHA, 3L).toString();

        assertThat(texto).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("toString continua util: mostra o municipioId")
    void toStringMostraMunicipioId() {
        String texto = new UsuarioInicialProperties(EMAIL, SENHA, 3L).toString();

        assertThat(texto).contains("municipioId=3");
    }
}
