package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Prova que o {@code StpInterfaceImpl} entrega ao {@code @SaCheckPermission} as permissoes da
 * claim {@code perms} do JWT da requisicao e nega por padrao (lista vazia) quando nao ha o que ler
 * (PROD-18).
 */
@ExtendWith(ContextoSaTokenExtension.class)
@DisplayName("StpInterfaceImpl le as permissoes da claim perms do JWT")
class StpInterfaceImplTest {

    private final StpInterfaceImpl stpInterface = new StpInterfaceImpl();

    @Test
    @DisplayName("devolve as permissoes da claim perms do token da requisicao")
    void devolveAsPermissoesDaClaimPerms() {
        ContextoSaTokenExtension.requisicaoComToken(ContextoSaTokenExtension.emitirToken(
                7L, List.of("relatorios:ocupacao:list", "relatorios:fila:list")));

        List<String> permissoes = stpInterface.getPermissionList(1L, "login");

        assertThat(permissoes).containsExactly("relatorios:ocupacao:list", "relatorios:fila:list");
    }

    @Test
    @DisplayName("loginId igual ao do token (comparado como texto) devolve as permissoes")
    void loginIdIgualComparadoComoTextoDevolvePermissoes() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("relatorios:ocupacao:list")));

        assertThat(stpInterface.getPermissionList("1", "login")).containsExactly("relatorios:ocupacao:list");
    }

    @Test
    @DisplayName("loginId diferente do token da requisicao devolve lista vazia sem excecao")
    void loginIdDiferenteDevolveListaVazia() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("relatorios:ocupacao:list")));

        assertThatCode(() -> assertThat(stpInterface.getPermissionList(2L, "login")).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sem a claim perms, devolve lista vazia")
    void semAClaimPermsDevolveListaVazia() {
        ContextoSaTokenExtension.requisicaoComToken(ContextoSaTokenExtension.emitirTokenSemClaims());

        assertThat(stpInterface.getPermissionList(1L, "login")).isEmpty();
    }

    @Test
    @DisplayName("sem token na requisicao, devolve lista vazia e nunca lanca excecao")
    void semTokenDevolveListaVaziaSemExcecao() {
        assertThatCode(() -> assertThat(stpInterface.getPermissionList(1L, "login")).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("com token malformado, devolve lista vazia e nunca lanca excecao")
    void comTokenMalformadoDevolveListaVaziaSemExcecao() {
        ContextoSaTokenExtension.requisicaoComToken("isto-nao-e-um-jwt");

        assertThatCode(() -> assertThat(stpInterface.getPermissionList(1L, "login")).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nao concede papeis: a lista de roles e sempre vazia")
    void listaDeRolesEVazia() {
        ContextoSaTokenExtension.requisicaoComToken(
                ContextoSaTokenExtension.emitirToken(7L, List.of("*")));

        assertThat(stpInterface.getRoleList(1L, "login")).isEmpty();
    }
}
