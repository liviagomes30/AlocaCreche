package org.alocacreche.relatorios.matricula;

/**
 * Espelha o enum StatusMatricula do modulo Matricula & Vagas/Creches (ainda nao implementado em
 * alocacreche-core - ver docs/arquitetura/visao-geral.md, diagrama de classes). Mantido aqui como
 * copia local porque este servico roda em processo e banco separados (ADR 0001, decisao 2) e nao
 * pode depender do jar do core.
 */
public enum StatusMatricula {
    PENDENTE,
    CONFIRMADA,
    EXPIRADA
}
