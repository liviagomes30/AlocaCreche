package org.alocacreche.relatorios.matricula.dto;

import java.time.LocalDate;

public record DistanciaMediaAlocacaoDTO(Long municipioId, LocalDate periodo, Double distanciaMediaKm) {
}
