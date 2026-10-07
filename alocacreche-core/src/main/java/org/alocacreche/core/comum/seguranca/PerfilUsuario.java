package org.alocacreche.core.comum.seguranca;

import java.util.List;

public enum PerfilUsuario {

    ADMIN(List.of("*")),
    GESTOR(List.of("relatorios:ocupacao:list", "relatorios:fila:list", "relatorios:matricula:list")),
    ATENDENTE(List.of(
            "inscricao:inscricao:add",
            "inscricao:fila:detail",
            "inscricao:responsavel:add",
            "inscricao:crianca:add"));

    private final List<String> permissoes;

    PerfilUsuario(List<String> permissoes) {
        this.permissoes = permissoes;
    }

    public List<String> permissoes() {
        return permissoes;
    }
}
