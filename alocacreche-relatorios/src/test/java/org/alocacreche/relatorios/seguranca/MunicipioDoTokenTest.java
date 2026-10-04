package org.alocacreche.relatorios.seguranca;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.exception.NotPermissionException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Prova que o municipio pedido so passa se for o da claim {@code municipioId} do token
 * (AUTH-17): igual passa, diferente ou ausente vira {@code NotPermissionException} (403).
 */
@ExtendWith(ContextoSaTokenExtension.class)
@DisplayName("MunicipioDoToken confere o municipio pedido contra a claim do token")
class MunicipioDoTokenTest {

    private final MunicipioDoToken municipioDoToken = new MunicipioDoToken();

    @Test
    @DisplayName("municipio igual ao do token passa")
    void municipioIgualAoDoTokenPassa() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("relatorios:ocupacao:list")));

        assertThatCode(() -> municipioDoToken.exigir(7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("municipio diferente do token lanca NotPermissionException")
    void municipioDiferenteLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("relatorios:ocupacao:list")));

        assertThatThrownBy(() -> municipioDoToken.exigir(8L)).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("claim municipioId ausente lanca NotPermissionException")
    void claimAusenteLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken(ContextoSaTokenExtension.emitirTokenSemClaims());

        assertThatThrownBy(() -> municipioDoToken.exigir(7L)).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("municipio pedido ausente lanca NotPermissionException")
    void municipioPedidoAusenteLancaNotPermissionException() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("relatorios:ocupacao:list")));

        assertThatThrownBy(() -> municipioDoToken.exigir(null)).isInstanceOf(NotPermissionException.class);
    }

    @Test
    @DisplayName("sem token na requisicao lanca NotPermissionException em vez de passar")
    void semTokenLancaNotPermissionException() {
        assertThatThrownBy(() -> municipioDoToken.exigir(7L)).isInstanceOf(NotPermissionException.class);
    }
}
