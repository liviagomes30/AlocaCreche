package org.alocacreche.core.matricula.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.alocacreche.core.matricula.FaixaEtaria;

public record CriarTurmaRequest(
        @Schema(description = "Creche à qual a turma pertence.", example = "1")
        @NotNull @Positive Long crecheId,
        @Schema(description = "Faixa etária atendida pela turma.", example = "MATERNAL")
        @NotNull FaixaEtaria faixaEtaria,
        @Schema(description = "Total de vagas da turma.", example = "20")
        @NotNull @Positive Integer vagasTotais) {
}
