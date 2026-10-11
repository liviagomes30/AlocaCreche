package org.alocacreche.core.matricula.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.alocacreche.core.comum.Endereco;

public record CriarCrecheRequest(
        @Schema(description = "Nome da creche.", example = "Creche Girassol")
        @NotBlank @Size(max = 200) String nome,
        @Schema(description = "Capacidade total de crianças.", example = "50")
        @NotNull @Positive Integer capacidade,
        @Schema(description = "Endereço; latitude e longitude alimentam o cálculo de proximidade.")
        @NotNull Endereco endereco) {
}
