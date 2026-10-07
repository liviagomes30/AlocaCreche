package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Permissoes de cada perfil fixo (AUTH-10).
 */
@DisplayName("PerfilUsuario")
class PerfilUsuarioTest {

    @Test
    @DisplayName("ADMIN devolve somente o curinga *")
    void adminDevolveCuringa() {
        assertThat(PerfilUsuario.ADMIN.permissoes()).containsExactly("*");
    }

    @Test
    @DisplayName("GESTOR devolve exatamente as tres permissoes de relatorios")
    void gestorDevolvePermissoesDeRelatorios() {
        assertThat(PerfilUsuario.GESTOR.permissoes()).containsExactlyInAnyOrder(
                "relatorios:ocupacao:list", "relatorios:fila:list", "relatorios:matricula:list");
    }

    @Test
    @DisplayName("ATENDENTE devolve exatamente as permissoes de inscricao, fila, responsavel e crianca")
    void atendenteDevolvePermissoesDeInscricao() {
        assertThat(PerfilUsuario.ATENDENTE.permissoes()).containsExactlyInAnyOrder(
                "inscricao:inscricao:add",
                "inscricao:fila:detail",
                "inscricao:responsavel:add",
                "inscricao:crianca:add");
    }

    @ParameterizedTest
    @EnumSource(PerfilUsuario.class)
    @DisplayName("a lista de permissoes devolvida e imutavel")
    void listaDePermissoesEImutavel(PerfilUsuario perfil) {
        assertThatThrownBy(() -> perfil.permissoes().add("modulo:funcionalidade:operacao"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
