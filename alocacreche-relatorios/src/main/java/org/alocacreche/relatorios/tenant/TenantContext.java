package org.alocacreche.relatorios.tenant;

import jakarta.persistence.EntityManager;

/**
 * Aplica o municipio corrente na sessao Postgres para a Row-Level Security (ver AD-002), escopado a
 * transacao atual (equivalente a SET LOCAL) via set_config parametrizado - evita concatenar o valor
 * diretamente na string do SQL, ao contrario de SET LOCAL, que nao aceita bind parameter.
 */
public final class TenantContext {

    private static final String QUERY = "SELECT set_config('app.current_tenant', ?1, true)";

    private TenantContext() {
    }

    public static void aplicar(EntityManager entityManager, Long municipioId) {
        entityManager
                .createNativeQuery(QUERY)
                .setParameter(1, municipioId.toString())
                .getSingleResult();
    }
}
