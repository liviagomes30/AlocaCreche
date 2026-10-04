package org.alocacreche.core.comum.seguranca;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alocacreche.seguranca.usuario-inicial")
public record UsuarioInicialProperties(String email, String senha, Long municipioId) {

    public boolean estaConfigurado() {
        return email != null && !email.isBlank()
                && senha != null && !senha.isBlank()
                && municipioId != null;
    }
}
