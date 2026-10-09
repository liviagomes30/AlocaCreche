package org.alocacreche.relatorios.matricula.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "matricula_relatorio")
@Getter
@NoArgsConstructor
public class MatriculaRelatorio {

    @Id
    @Column(name = "matricula_id")
    private Long matriculaId;

    @Column(name = "inscricao_id", nullable = false)
    private Long inscricaoId;

    @Column(name = "turma_id", nullable = false)
    private Long turmaId;

    @Column(name = "creche_id", nullable = false)
    private Long crecheId;

    @Column(name = "municipio_id", nullable = false)
    private Long municipioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StatusMatricula status;

    @Column(name = "distancia_km", nullable = false)
    private Double distanciaKm;

    @Column(name = "data_convocacao", nullable = false)
    private LocalDate dataConvocacao;

    @Column(name = "data_confirmacao")
    private LocalDate dataConfirmacao;

    @Column(name = "ultima_sequencia_aplicada", nullable = false)
    private Long ultimaSequenciaAplicada;

    @Column(name = "atualizado_em", nullable = false)
    private LocalDateTime atualizadoEm;
}
