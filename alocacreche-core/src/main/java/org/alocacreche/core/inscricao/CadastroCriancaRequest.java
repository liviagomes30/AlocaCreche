package org.alocacreche.core.inscricao;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Corpo de {@code POST /api/v1/inscricao/criancas}: a crianca e o responsavel ao qual pertence. */
public record CadastroCriancaRequest(
        @Schema(description = "Responsável ao qual a criança pertence.", example = "5")
        @NotNull @Positive Long responsavelId,
        @Schema(description = "Nome da criança (fictício no exemplo).", example = "Maria Exemplo")
        @NotBlank @Size(max = 200) String nome,
        @Schema(description = "Data de nascimento, no passado.", example = "2024-03-15")
        @NotNull @Past LocalDate dataNascimento) {

    /** Dados da crianca no formato reaproveitado pelo cadastro inline da inscricao. */
    public CriancaDTO dados() {
        return new CriancaDTO(nome, dataNascimento);
    }

    @Override
    public String toString() {
        return "CadastroCriancaRequest[responsavelId=" + responsavelId + "]";
    }
}
