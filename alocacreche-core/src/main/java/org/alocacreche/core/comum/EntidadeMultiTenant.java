package org.alocacreche.core.comum;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Base de toda entidade de dominio tenant-scoped - evita repetir {@code id}/{@code municipio_id}
 * (identicos, mesma anotacao) em cada entidade que carrega a regra "toda entidade de dominio
 * carrega municipio_id" (CLAUDE.md).
 *
 * <p>Nao se aplica a {@code Municipio} (e o proprio discriminador de tenant, nao carrega a
 * propria coluna) nem a {@code Inscricao} (carrega o tenant via relacao direta com
 * {@code Municipio}, ADR 0001 decisao 16, nao por coluna escalar).
 *
 * <p><b>Isolamento multi-tenant (ADR 0001, decisao 14):</b> {@code municipioId} e so a coluna -
 * quem de fato impede uma query de cruzar tenant e a policy de Row-Level Security do Postgres
 * definida em {@code src/main/resources/schema.sql} (com {@code FORCE ROW LEVEL SECURITY}, entao
 * nem o dono da tabela escapa dela). Nenhuma entidade usa {@code @TenantId} do Hibernate de
 * proposito - a convencao deste projeto e chamar {@link org.alocacreche.core.comum.tenant.TenantContext#aplicar}
 * explicitamente no inicio de todo metodo transacional que toque uma entidade tenant-scoped
 * (mesmo padrao ja usado em {@code alocacreche-relatorios}), nao um resolver automatico de sessao.
 * Sem essa chamada a RLS bloqueia a query inteira (fail-closed) - ver
 * {@code docs/arquitetura/notas-tecnicas.md#multi-tenancy-com-row-level-security-postgresql} para
 * o racional completo, incluindo o cuidado com jobs {@code @Scheduled} (sem tenant de sessao,
 * precisam de uma transacao por municipio).
 */
@MappedSuperclass
@Getter
@Setter
public abstract class EntidadeMultiTenant {

    /**
     * Sem setter publico de proposito - id gerado por {@code IDENTITY} nao deve ser reatribuido
     * apos o load, mesmo racional do {@code @Version} de {@code Turma}.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "municipio_id", nullable = false)
    private Long municipioId;
}
