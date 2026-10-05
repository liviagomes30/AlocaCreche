package org.alocacreche.core.matricula.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.alocacreche.core.comum.Endereco;

public record CriarCrecheRequest(
        @NotBlank @Size(max = 200) String nome,
        @NotNull @Positive Integer capacidade,
        @NotNull Endereco endereco) {
}
