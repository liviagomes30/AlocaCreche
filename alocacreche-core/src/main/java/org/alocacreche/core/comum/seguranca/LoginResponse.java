package org.alocacreche.core.comum.seguranca;

import java.time.Instant;

public record LoginResponse(String token, String tipo, Instant expiraEm) {

    public static final String TIPO_BEARER = "Bearer";
}
