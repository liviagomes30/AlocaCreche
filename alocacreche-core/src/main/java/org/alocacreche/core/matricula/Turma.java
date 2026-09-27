package org.alocacreche.core.matricula;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.comum.EntidadeMultiTenant;

/**
 * {@code @Version} e o mecanismo de lock otimista que garante que duas criancas nunca sejam
 * atribuidas a mesma vaga - nao remover nem substituir por controle manual (ADR 0001, decisao 1).
 */
@Entity
@Table(name = "turma")
@Getter
@Setter
@NoArgsConstructor
public class Turma extends EntidadeMultiTenant {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creche_id", nullable = false)
    private Creche creche;

    @Enumerated(EnumType.STRING)
    @Column(name = "faixa_etaria", nullable = false, length = 20)
    private FaixaEtaria faixaEtaria;

    @Column(name = "vagas_totais", nullable = false)
    private Integer vagasTotais;

    @Column(name = "vagas_disponiveis", nullable = false)
    private Integer vagasDisponiveis;

    /**
     * Sem setter publico de proposito - e o Hibernate quem gerencia esse valor a cada update
     * para o lock otimista funcionar. Um setVersion(...) manual (teste, merge de estado, etc.)
     * neutralizaria silenciosamente a garantia de nao duplicar vaga (ADR 0001, decisao 1).
     */
    @Version
    @Setter(AccessLevel.NONE)
    @Column(nullable = false)
    private Long version;
}
