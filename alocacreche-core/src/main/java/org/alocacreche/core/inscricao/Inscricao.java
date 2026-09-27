package org.alocacreche.core.inscricao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.notificacao.Municipio;

/**
 * Referencia {@link Municipio} diretamente (nao so transitivamente via Turma/Creche) - ADR 0001,
 * decisao 16: a familia se vincula ao municipio no momento da inscricao, antes de ser alocada a
 * uma creche especifica. A coluna {@code municipio_id} desta tabela vem dessa relacao, nao de um
 * campo escalar solto (diferente das demais entidades deste modulo).
 *
 * <p>{@code scorePrioridade} persistido aqui e cache de exibicao, nunca fonte de verdade da
 * alocacao - quem recalcula on-the-fly e {@code alocarProximoElegivel} no modulo Matricula.
 *
 * <p>{@code rendaPerCapita} e {@code criterioVulnerabilidade} sao dado sensivel LGPD - nunca
 * logar em claro (ver CLAUDE.md). Sem {@code @ToString}/{@code @Data} de proposito, para nao
 * vazar isso num log acidental de debug.
 */
@Entity
@Table(name = "inscricao")
@Getter
@Setter
@NoArgsConstructor
public class Inscricao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "municipio_id", nullable = false)
    private Municipio municipio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crianca_id", nullable = false)
    private Crianca crianca;

    @Column(name = "data_inscricao", nullable = false)
    private LocalDateTime dataInscricao = LocalDateTime.now();

    @Column(name = "renda_per_capita", precision = 12, scale = 2)
    private BigDecimal rendaPerCapita;

    @Column(name = "criterio_vulnerabilidade")
    private Boolean criterioVulnerabilidade = Boolean.FALSE;

    @Column(name = "score_prioridade")
    private Double scorePrioridade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusInscricao status = StatusInscricao.AGUARDANDO;
}
