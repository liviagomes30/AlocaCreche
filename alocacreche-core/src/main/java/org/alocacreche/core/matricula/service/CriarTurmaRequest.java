package org.alocacreche.core.matricula.service;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.alocacreche.core.matricula.FaixaEtaria;

public record CriarTurmaRequest(
        @NotNull @Positive Long crecheId,
        @NotNull FaixaEtaria faixaEtaria,
        @NotNull @Positive Integer vagasTotais) {
}
