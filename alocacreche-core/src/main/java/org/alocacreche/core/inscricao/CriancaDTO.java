package org.alocacreche.core.inscricao;

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
        @NotBlank @Size(max = 200) String nome, @NotNull @Past LocalDate dataNascimento) {

    @Override
    public String toString() {
        return "CriancaDTO[]";
    }
}
