package org.alocacreche.core.inscricao;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
        Long municipioId,
        Long criancaId,
        @Valid ResponsavelDTO responsavel,
        @Valid CriancaDTO crianca,
        @NotNull @Positive Long turmaId,
        @NotNull @PositiveOrZero BigDecimal rendaPerCapita,
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
