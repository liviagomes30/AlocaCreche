package org.alocacreche.core.inscricao;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * Corpo de entrada da inscricao. Aceita exatamente um de dois modos: {@code criancaId} (crianca ja
 * cadastrada) ou {@code responsavel} + {@code crianca} (cadastro inline). O {@code municipioId} e
 * opcional; quando informado precisa ser o mesmo do token.
 */
public record InscricaoDTO(
        @Schema(description = "Opcional; quando informado precisa ser o município do token.", example = "1")
        Long municipioId,
        @Schema(description = "Modo 1: criança já cadastrada. Não combine com responsavel e crianca.", example = "10")
        Long criancaId,
        @Schema(description = "Modo 2 (cadastro inline): responsável. Exige também crianca.")
        @Valid ResponsavelDTO responsavel,
        @Schema(description = "Modo 2 (cadastro inline): criança. Exige também responsavel.")
        @Valid CriancaDTO crianca,
        @Schema(description = "Turma em cuja fila a criança entra.", example = "1")
        @NotNull @Positive Long turmaId,
        @Schema(description = "Renda familiar per capita (dado sensível; valor fictício no exemplo).", example = "450.00")
        @NotNull @PositiveOrZero BigDecimal rendaPerCapita,
        @Schema(description = "Critério de vulnerabilidade social (dado sensível).", example = "false")
        @NotNull Boolean criterioVulnerabilidade) {

    /**
     * Garante o modo unico de identificacao da crianca: por id ou inline completo, nunca os dois e
     * nunca nenhum.
     */
    @JsonIgnore
    @AssertTrue(message = "informe criancaId ou responsavel e crianca, nunca os dois modos")
    public boolean isModoDeCriancaValido() {
        boolean porId = criancaId != null;
        boolean algumInline = responsavel != null || crianca != null;
        boolean inlineCompleto = responsavel != null && crianca != null;
        return porId ? !algumInline : inlineCompleto;
    }

    @Override
    public String toString() {
        return "InscricaoDTO[turmaId=" + turmaId + "]";
    }
}
