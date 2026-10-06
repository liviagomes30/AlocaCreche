package org.alocacreche.core.inscricao;

public record InscricaoRegistradaResponse(
        Long inscricaoId, Integer posicao, Double scorePrioridade, StatusInscricao status) {
}
