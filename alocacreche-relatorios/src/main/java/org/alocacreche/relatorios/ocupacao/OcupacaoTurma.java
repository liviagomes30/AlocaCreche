package org.alocacreche.relatorios.ocupacao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ocupacao_turma")
@Getter
@NoArgsConstructor
public class OcupacaoTurma {

    @Id
    @Column(name = "turma_id")
    private Long turmaId;

    @Column(name = "municipio_id", nullable = false)
    private Long municipioId;

    @Column(name = "creche_id", nullable = false)
    private Long crecheId;

    @Column(name = "vagas_disponiveis", nullable = false)
    private Integer vagasDisponiveis;

    @Column(name = "ultima_sequencia_aplicada", nullable = false)
    private Long ultimaSequenciaAplicada;

    @Column(name = "atualizado_em", nullable = false)
    private LocalDateTime atualizadoEm;
}
