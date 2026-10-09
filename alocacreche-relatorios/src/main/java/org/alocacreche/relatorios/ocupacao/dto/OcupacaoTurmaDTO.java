package org.alocacreche.relatorios.ocupacao.dto;

import java.time.LocalDateTime;

public record OcupacaoTurmaDTO(
        Long turmaId,
        Long crecheId,
        Long municipioId,
        Integer vagasDisponiveis,
        Long ultimaSequenciaAplicada,
        LocalDateTime atualizadoEm) {
}
