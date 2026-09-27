package org.alocacreche.core.comum.tenant;

import jakarta.persistence.EntityManager;
import java.util.Objects;

/**
 * Aplica o municipio corrente na sessao Postgres para a Row-Level Security (ADR 0001, decisao 14),
 * escopado a transacao atual (equivalente a SET LOCAL) via set_config parametrizado - evita
 * concatenar o valor diretamente na string do SQL, ao contrario de SET LOCAL, que nao aceita bind
 * parameter. Mesmo padrao de {@code org.alocacreche.relatorios.tenant.TenantContext}.
 *
 * <p>Chame no inicio de todo metodo transacional que toque uma entidade tenant-scoped (ver
 * {@link org.alocacreche.core.comum.EntidadeMultiTenant}), antes de qualquer repositorio/query -
 * sem isso, a policy de RLS bloqueia a query inteira (fail-closed), nunca retorna dado de outro
 * municipio. Jobs agendados (sem tenant de sessao) precisam abrir uma transacao por municipio e
 * chamar isto antes de processar - ver notas-tecnicas.md#multi-tenancy-com-row-level-security-postgresql.
 */
public final class TenantContext {

    private static final String QUERY = "SELECT set_config('app.current_tenant', ?1, true)";

    private TenantContext() {
    }

    public static void aplicar(EntityManager entityManager, Long municipioId) {
        Objects.requireNonNull(municipioId, "municipioId nao pode ser nulo");
        entityManager
                .createNativeQuery(QUERY)
                .setParameter(1, municipioId.toString())
                .getSingleResult();
    }
}
