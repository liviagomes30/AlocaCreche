package org.alocacreche.core.comum.tenant;

import jakarta.persistence.EntityManager;
import java.util.Objects;

public final class TenantContext {

    private static final String DEFINIR_TENANT_ATUAL_ESCOPADO_A_TRANSACAO =
            "SELECT set_config('app.current_tenant', ?1, true)";

    private TenantContext() {
    }

    public static void aplicar(EntityManager entityManager, Long municipioId) {
        Objects.requireNonNull(municipioId, "municipioId nao pode ser nulo");
        entityManager
                .createNativeQuery(DEFINIR_TENANT_ATUAL_ESCOPADO_A_TRANSACAO)
                .setParameter(1, municipioId.toString())
                .getSingleResult();
    }
}
