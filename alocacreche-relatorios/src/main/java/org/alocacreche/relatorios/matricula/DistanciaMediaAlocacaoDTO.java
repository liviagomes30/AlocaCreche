package org.alocacreche.relatorios.matricula;

import java.time.LocalDate;

/**
 * Forma de resposta do relatorio de distancia media de alocacao. Nunca expoe MatriculaRelatorio
 * (entidade JPA) diretamente pela API.
 */
public record DistanciaMediaAlocacaoDTO(Long municipioId, LocalDate periodo, Double distanciaMediaKm) {
}
