package org.alocacreche.relatorios.filaespera;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Projecao de leitura do estado de uma inscricao na fila de espera (data de entrada e, quando
 * aplicavel, data de saida). Chave primaria = inscricaoId (chave de negocio, sem surrogate id - e
 * uma projecao, nao um dono de dominio). Mantida por upsert idempotente em
 * FilaEsperaInscricaoRepository; nunca criada/atualizada via save() direto - por isso nao ha
 * setters, so getters (mutar via setter fora do fluxo de upsert quebraria a garantia de
 * idempotencia).
 *
 * <p>Isolada por Row-Level Security (municipio_id) - ver schema.sql.
 */
@Entity
@Table(name = "fila_espera_inscricao")
@Getter
@NoArgsConstructor
public class FilaEsperaInscricao {

    @Id
    @Column(name = "inscricao_id")
    private Long inscricaoId;

    @Column(name = "turma_id", nullable = false)
    private Long turmaId;

    @Column(name = "creche_id", nullable = false)
    private Long crecheId;

    @Column(name = "municipio_id", nullable = false)
    private Long municipioId;

    @Column(name = "data_entrada", nullable = false)
    private LocalDate dataEntrada;

    @Column(name = "data_saida")
    private LocalDate dataSaida;

    @Column(name = "ultima_sequencia_aplicada", nullable = false)
    private Long ultimaSequenciaAplicada;

    @Column(name = "atualizado_em", nullable = false)
    private LocalDateTime atualizadoEm;
}
