package org.alocacreche.relatorios.seguranca.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * No perfil prod a aplicacao nao sobe com o segredo JWT padrao do repositorio ou com um segredo
 * curto: quem conhece o placeholder poderia forjar tokens de qualquer municipio.
 */
@Component
@Profile("prod")
public class SegredoJwtProducaoValidador {

    static final String SEGREDO_PADRAO = "troque-este-segredo-antes-de-qualquer-deploy";
    static final int TAMANHO_MINIMO = 32;

    private final String segredo;

    public SegredoJwtProducaoValidador(@Value("${sa-token.jwt-secret-key:}") String segredo) {
        this.segredo = segredo;
    }

    @PostConstruct
    void validar() {
        if (segredo == null || segredo.isBlank() || SEGREDO_PADRAO.equals(segredo) || segredo.length() < TAMANHO_MINIMO) {
            throw new IllegalStateException(
                    "SA_TOKEN_JWT_SECRET deve ser definido com pelo menos " + TAMANHO_MINIMO
                            + " caracteres e diferente do valor padrao no perfil prod");
        }
    }
}
