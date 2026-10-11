package org.alocacreche.core.matricula.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AlocarVagaRequest(
        @Schema(description = "Turma cuja próxima criança elegível da fila recebe a vaga.", example = "1")
        @NotNull @Positive Long turmaId) {
}
