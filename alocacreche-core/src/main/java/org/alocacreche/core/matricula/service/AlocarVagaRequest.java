package org.alocacreche.core.matricula.service;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AlocarVagaRequest(@NotNull @Positive Long turmaId) {
}
