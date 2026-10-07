package org.alocacreche.core.inscricao;

import jakarta.validation.constraints.NotBlank;

public record ResponsavelDTO(
        @NotBlank String nome,
        @NotBlank String cpf,
        String telefone,
        String email) {
}