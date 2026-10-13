package org.alocacreche.relatorios.ocupacao.event;

import java.time.LocalDateTime;

public record VagaLiberadaEvent(
        Long eventId,
        String tipoEvento,
        Long municipioId,
        Long turmaId,
        Long crecheId,
        Integer vagasDisponiveis,
        String faixaEtaria,
        String crecheNome,
        String bairro,
        LocalDateTime ocorridoEm) {

    /** Evento sem os dados publicos da creche (mensagens antigas ou de teste): faixa, nome e bairro ficam nulos. */
    public VagaLiberadaEvent(
            Long eventId,
            String tipoEvento,
            Long municipioId,
            Long turmaId,
            Long crecheId,
            Integer vagasDisponiveis,
            LocalDateTime ocorridoEm) {
        this(eventId, tipoEvento, municipioId, turmaId, crecheId, vagasDisponiveis, null, null, null, ocorridoEm);
    }
}
