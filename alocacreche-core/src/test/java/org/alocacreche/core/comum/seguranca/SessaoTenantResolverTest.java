package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.exception.NotPermissionException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Prova que o municipio da sessao vem da claim {@code municipioId} do JWT e que a ausencia dela
 * vira {@code NotPermissionException}, traduzida em HTTP 403 (PROD-18).
 */
@ExtendWith(ContextoSaTokenExtension.class)
@DisplayName("SessaoTenantResolver resolve o municipio da claim do JWT")
class SessaoTenantResolverTest {

    private final SessaoTenantResolver resolver = new SessaoTenantResolver();

    @Test
    @DisplayName("devolve o municipioId da claim como Long")
    void devolveOMunicipioIdDaClaim() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("*")));

        Long municipioId = resolver.municipioIdAtual();

        assertThat(municipioId).isEqualTo(7L);
    }

    @Test
    @DisplayName("claim municipioId ausente lanca NotPermissionException")
    void claimAusenteLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken(ContextoSaTokenExtension.emitirTokenSemClaims());

        assertThatThrownBy(resolver::municipioIdAtual).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("sem token na requisicao lanca NotPermissionException")
    void semTokenLancaNotPermissionException() {
        assertThatThrownBy(resolver::municipioIdAtual).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("token malformado lanca NotPermissionException")
    void tokenMalformadoLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken("isto-nao-e-um-jwt");

        assertThatThrownBy(resolver::municipioIdAtual).isInstanceOf(NotPermissionException.class);
    }
}
