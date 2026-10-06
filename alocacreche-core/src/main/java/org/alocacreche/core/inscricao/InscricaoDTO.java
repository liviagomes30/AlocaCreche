package org.alocacreche.core.inscricao;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record InscricaoDTO(
        @NotNull Long municipioId,
        @NotNull Long criancaId,
        BigDecimal rendaPerCapita,
        Boolean criterioVulnerabilidade) {
}