package org.alocacreche.core.inscricao;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CriancaDTO(
        @NotNull Long responsavelId,
        @NotBlank String nome,
        @NotNull LocalDate dataNascimento) {
}