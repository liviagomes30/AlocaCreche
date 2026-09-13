package org.alocacreche.relatorios.ocupacao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Projecao de leitura da ocupacao de uma turma. Chave primaria = turmaId (chave de negocio, sem
 * surrogate id - e uma projecao, nao um dono de dominio). Mantida por upsert idempotente em
 * OcupacaoTurmaRepository; nunca criada/atualizada via save() direto.
 *
 * <p>Isolada por Row-Level Security (municipio_id) - ver schema.sql.
 */
@Entity
@Table(name = "ocupacao_turma")
@Getter
@Setter
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
