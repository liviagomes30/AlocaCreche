package org.alocacreche.core.matricula;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.alocacreche.core.comum.EntidadeMultiTenant;

@Entity
@Table(name = "matricula")
@Getter
@Setter
@NoArgsConstructor
public class Matricula extends EntidadeMultiTenant {

    /**
     * ID cru, nao @OneToOne - Inscricao vive no pacote inscricao, dono de outro integrante.
     * Referencia por objeto criaria dependencia de pacote em ambas as direcoes (matricula ->
     * inscricao e inscricao -> matricula via FilaEspera.turmaId), o que junta os dois pacotes
     * num ciclo. Ver docs/adr/0004-divisao-equipe-tres-integrantes.md (revisao de arquitetura).
     */
    @Column(name = "inscricao_id", nullable = false, unique = true)
    private Long inscricaoId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "turma_id", nullable = false)
    private Turma turma;

    @Column(name = "data_convocacao", nullable = false)
    private LocalDateTime dataConvocacao = LocalDateTime.now();

    @Column(name = "prazo_confirmacao", nullable = false)
    private LocalDateTime prazoConfirmacao;

    @Column(name = "data_confirmacao")
    private LocalDateTime dataConfirmacao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusMatricula status = StatusMatricula.PENDENTE;

    /**
     * Nao esta no diagrama de classes original - adicionado porque o contrato do evento
     * MatriculaAtualizada (MSRelatorios) precisa desse valor pra
     * calcular distancia media de alocacao sem regeocodificar. Calculado via
     * GeolocalizacaoService no momento da alocacao. Confirmar com a stack de Relatorios antes de
     * considerar este campo definitivo (docs/adr/0004-divisao-equipe-tres-integrantes.md).
     */
    @Column(name = "distancia_km")
    private Double distanciaKm;
}
