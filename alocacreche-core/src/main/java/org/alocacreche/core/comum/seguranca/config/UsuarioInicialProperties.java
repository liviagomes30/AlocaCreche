package org.alocacreche.core.comum.seguranca.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alocacreche.seguranca.usuario-inicial")
public record UsuarioInicialProperties(String email, String senha, Long municipioId) {

    @Override
    public String toString() {
        return "UsuarioInicialProperties[municipioId=" + municipioId + ", email=<omitido>, senha=<omitida>]";
    }

    public boolean estaConfigurado() {
        return email != null && !email.isBlank()
                && senha != null && !senha.isBlank()
                && municipioId != null;
    }
}
