package org.alocacreche.relatorios.filaespera.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
