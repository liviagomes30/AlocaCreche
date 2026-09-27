package org.alocacreche.core.inscricao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.comum.EntidadeMultiTenant;

@Entity
@Table(name = "fila_espera")
@Getter
@Setter
@NoArgsConstructor
public class FilaEspera extends EntidadeMultiTenant {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inscricao_id", nullable = false, unique = true)
    private Inscricao inscricao;

    /**
     * ID cru, nao @ManyToOne - Turma vive no pacote matricula, dono de outro integrante.
     * Referencia por objeto criaria dependencia de pacote em ambas as direcoes (inscricao ->
     * matricula e matricula -> inscricao via Matricula.inscricaoId), o que junta os dois pacotes
     * num ciclo. Ver docs/adr/0004-divisao-equipe-tres-integrantes.md (revisao de arquitetura).
     */
    @Column(name = "turma_id", nullable = false)
    private Long turmaId;

    @Column(nullable = false)
    private Integer posicao;

    @Column(name = "data_entrada", nullable = false)
    private LocalDateTime dataEntrada = LocalDateTime.now();
}
