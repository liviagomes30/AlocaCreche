package org.alocacreche.core.inscricao;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Dados da crianca, usados no cadastro inline de {@code POST .../inscricoes} e embutidos em
 * {@link CadastroCriancaRequest}.
 */
public record CriancaDTO(
        @Schema(description = "Nome da criança (fictício no exemplo).", example = "Maria Exemplo")
        @NotBlank @Size(max = 200) String nome,
        @Schema(description = "Data de nascimento, no passado.", example = "2024-03-15")
        @NotNull @Past LocalDate dataNascimento) {

    @Override
    public String toString() {
        return "CriancaDTO[]";
    }
}
