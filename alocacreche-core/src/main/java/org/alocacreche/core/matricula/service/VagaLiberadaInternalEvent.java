package org.alocacreche.core.matricula.service;

/**
 * Evento de aplicacao, so em memoria do processo (ADR 0001, decisao 3) - distinto do evento
 * duravel {@code vaga.liberada} gravado na Outbox. Se a aplicacao cair entre o commit e o
 * listener, ele se perde; quem cobre esse caso e o job de reconciliacao (TODO em
 * {@link AlocacaoAutomaticaListener}).
 */
public record VagaLiberadaInternalEvent(Long municipioId, Long turmaId) {
}
