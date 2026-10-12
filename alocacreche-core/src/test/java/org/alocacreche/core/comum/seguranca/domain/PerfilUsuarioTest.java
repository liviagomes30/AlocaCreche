package org.alocacreche.core.comum.seguranca.domain;

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
    @DisplayName("GESTOR devolve as permissoes de relatorios e da operacao de matricula e vagas")
    void gestorDevolvePermissoesDeRelatoriosEMatricula() {
        assertThat(PerfilUsuario.GESTOR.permissoes()).containsExactlyInAnyOrder(
                "relatorios:ocupacao:list",
                "relatorios:fila:list",
                "relatorios:matricula:list",
                "matricula:creche:add",
                "matricula:turma:add",
                "matricula:creche:list",
                "matricula:turma:list",
                "matricula:matricula:list",
                "inscricao:responsavel:list",
                "inscricao:crianca:list",
                "inscricao:inscricao:list",
                "matricula:vaga:alocar",
                "matricula:matricula:confirmar",
                "matricula:matricula:status");
    }

    @Test
    @DisplayName("ATENDENTE devolve as permissoes de inscricao e as de atendimento da matricula")
    void atendenteDevolvePermissoesDeInscricao() {
        assertThat(PerfilUsuario.ATENDENTE.permissoes()).containsExactlyInAnyOrder(
                "inscricao:inscricao:add",
                "inscricao:fila:detail",
                "inscricao:responsavel:add",
                "inscricao:crianca:add",
                "inscricao:responsavel:list",
                "inscricao:crianca:list",
                "inscricao:inscricao:list",
                "matricula:creche:list",
                "matricula:turma:list",
                "matricula:matricula:list",
                "matricula:matricula:status");
    }

    @ParameterizedTest
    @EnumSource(PerfilUsuario.class)
    @DisplayName("a lista de permissoes devolvida e imutavel")
    void listaDePermissoesEImutavel(PerfilUsuario perfil) {
        assertThatThrownBy(() -> perfil.permissoes().add("modulo:funcionalidade:operacao"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
