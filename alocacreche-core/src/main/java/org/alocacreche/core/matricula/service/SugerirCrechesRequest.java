package org.alocacreche.core.matricula.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.alocacreche.core.matricula.FaixaEtaria;

/**
 * Coordenadas ja cadastradas da familia (ADR 0006: sem geocodificacao ao vivo). {@code limite}
 * ausente vira {@link #LIMITE_PADRAO}.
 */
public record SugerirCrechesRequest(
        @Schema(description = "Latitude da residência da família.", example = "-23.5505")
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @Schema(description = "Longitude da residência da família.", example = "-46.6333")
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @Schema(description = "Faixa etária da criança.", example = "MATERNAL")
        @NotNull FaixaEtaria faixaEtaria,
        @Schema(description = "Quantidade máxima de creches devolvidas (1 a 20; padrão 5).", example = "5")
        @Min(1) @Max(20) Integer limite) {

    public static final int LIMITE_PADRAO = 5;

    public int limiteEfetivo() {
        return limite == null ? LIMITE_PADRAO : limite;
    }
}
