package org.alocacreche.relatorios.ocupacao.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

public record PainelPublicoDTO(
        Long municipioId,
        Long totalVagasDisponiveis,
        Long totalCrechesComVaga,
        Long totalTurmas,
        LocalDateTime atualizadoEm)
        implements Serializable {

    private static final long serialVersionUID = 1L;
}
