package org.alocacreche.core.comum.seguranca.domain;

import java.util.List;

public enum PerfilUsuario {

    ADMIN(List.of("*")),
    GESTOR(List.of(
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
            "matricula:matricula:status")),
    ATENDENTE(List.of(
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
            "matricula:matricula:status"));

    private final List<String> permissoes;

    PerfilUsuario(List<String> permissoes) {
        this.permissoes = permissoes;
    }

    public List<String> permissoes() {
        return permissoes;
    }
}
