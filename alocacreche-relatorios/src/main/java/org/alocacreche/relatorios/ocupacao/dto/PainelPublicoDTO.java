package org.alocacreche.relatorios.ocupacao.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Painel publico (sem login): so agregados e informacao publica das creches, nunca dados de pessoas.
 * {@code familiasAguardando} e a espera media ficam nulos quando ha poucas familias (menos de 5), para ninguem ser
 * identificado por exclusao; nesse caso {@code familiasAguardandoAbaixoDoMinimo} e verdadeiro.
 */
public record PainelPublicoDTO(
        Long municipioId,
        Long totalVagasDisponiveis,
        Long totalCrechesComVaga,
        Long totalTurmas,
        LocalDateTime atualizadoEm,
        List<VagasPorFaixa> vagasPorFaixa,
        List<CrecheComVaga> crechesComVaga,
        Long familiasAguardando,
        Boolean familiasAguardandoAbaixoDoMinimo,
        Double esperaMediaDias)
        implements Serializable {

    private static final long serialVersionUID = 2L;

    public record VagasPorFaixa(String faixaEtaria, Long vagasDisponiveis, Long turmas) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record CrecheComVaga(Long crecheId, String nome, String bairro, String faixaEtaria, Integer vagasDisponiveis)
            implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
