package org.alocacreche.core.inscricao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.comum.EntidadeMultiTenant;

@Entity
@Table(
        name = "fila_espera",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_fila_espera_turma_posicao", columnNames = {"turma_id", "posicao"}))
@Getter
@Setter
@NoArgsConstructor
public class FilaEspera extends EntidadeMultiTenant {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inscricao_id", nullable = false, unique = true)
    private Inscricao inscricao;


    @Column(name = "turma_id", nullable = false)
    private Long turmaId;

    @Column(nullable = false)
    private Integer posicao;

    @Column(name = "data_entrada", nullable = false)
    private LocalDateTime dataEntrada = LocalDateTime.now();
}
