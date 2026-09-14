package org.alocacreche.relatorios.ocupacao;

import java.time.LocalDateTime;

/**
 * Forma de resposta da consulta de ocupação. Nunca expõe {@link OcupacaoTurma} (entidade JPA)
 * diretamente pela API.
 */
public record OcupacaoTurmaDTO(
        Long turmaId,
        Long crecheId,
        Long municipioId,
        Integer vagasDisponiveis,
        Long ultimaSequenciaAplicada,
        LocalDateTime atualizadoEm) {
}
