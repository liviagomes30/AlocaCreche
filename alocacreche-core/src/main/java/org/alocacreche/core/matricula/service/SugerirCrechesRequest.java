package org.alocacreche.core.matricula.service;

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
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull FaixaEtaria faixaEtaria,
        @Min(1) @Max(20) Integer limite) {

    public static final int LIMITE_PADRAO = 5;

    public int limiteEfetivo() {
        return limite == null ? LIMITE_PADRAO : limite;
    }
}
