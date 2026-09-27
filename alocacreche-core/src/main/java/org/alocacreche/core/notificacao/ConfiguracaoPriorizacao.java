package org.alocacreche.core.notificacao;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Objeto de Valor imutavel na intencao de uso (ADR 0001, decisao 15) - embutido em
 * {@link Municipio}, nunca uma entidade com tabela propria. Pesos usados por
 * {@code calcularScorePrioridade} (modulo Inscricao) e recalculados on-the-fly por
 * {@code alocarProximoElegivel} (modulo Matricula) - nunca hardcode esses pesos em codigo.
 *
 * <p>Use {@link #builder()} em vez do construtor posicional - os 4 pesos sao todos
 * {@code Double}, entao a ordem nao tem nenhuma checagem do compilador para evitar troca
 * acidental (ex.: pesoRenda por pesoDistancia).
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class ConfiguracaoPriorizacao {

    private static final double TOLERANCIA_SOMA_PESOS = 0.001;

    @Column(name = "peso_renda")
    private Double pesoRenda;

    @Column(name = "peso_vulnerabilidade")
    private Double pesoVulnerabilidade;

    @Column(name = "peso_distancia")
    private Double pesoDistancia;

    @Column(name = "peso_irmao_matriculado")
    private Double pesoIrmaoMatriculado;

    /**
     * Invariante do proprio Objeto de Valor: os 4 pesos precisam estar preenchidos, nao-negativos
     * e somar 1.0 (com tolerancia de ponto flutuante) - senao o score calculado por
     * {@code calcularScorePrioridade} (modulo Inscricao) fica sem significado (ex.: pesos
     * negativos invertendo prioridade, ou soma != 1.0 distorcendo a escala do score). Chamado por
     * {@code Municipio} antes de persistir/atualizar, mas fica aqui - nao em {@code Municipio} -
     * porque e a propria VO que sabe o que faz um conjunto de pesos valido.
     *
     * @throws IllegalStateException se algum peso estiver nulo, negativo, ou a soma dos 4 nao for
     *     1.0 dentro da tolerancia
     */
    public void validar() {
        if (pesoRenda == null
                || pesoVulnerabilidade == null
                || pesoDistancia == null
                || pesoIrmaoMatriculado == null) {
            throw new IllegalStateException(
                    "ConfiguracaoPriorizacao precisa ter todos os pesos preenchidos");
        }
        if (pesoRenda < 0 || pesoVulnerabilidade < 0 || pesoDistancia < 0 || pesoIrmaoMatriculado < 0) {
            throw new IllegalStateException(
                    "Pesos de ConfiguracaoPriorizacao nao podem ser negativos");
        }
        double soma = pesoRenda + pesoVulnerabilidade + pesoDistancia + pesoIrmaoMatriculado;
        if (Math.abs(soma - 1.0) > TOLERANCIA_SOMA_PESOS) {
            throw new IllegalStateException(
                    "Soma dos pesos de ConfiguracaoPriorizacao deve ser 1.0, mas foi " + soma);
        }
    }
}
