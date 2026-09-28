package org.alocacreche.relatorios.matricula;

import java.time.LocalDate;

public record DistanciaMediaAlocacaoDTO(Long municipioId, LocalDate periodo, Double distanciaMediaKm) {
}
