package org.alocacreche.core.matricula.service;

public record CrecheProximaResponse(
        Long crecheId, String nome, String bairro, Long turmaId, Integer vagasDisponiveis, Double distanciaKm) {
}
