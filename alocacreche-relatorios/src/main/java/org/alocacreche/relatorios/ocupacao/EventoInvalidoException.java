package org.alocacreche.relatorios.ocupacao;

import org.alocacreche.relatorios.evento.EventoDominioInvalidoException;

/**
 * Sinaliza payload do evento VagaLiberada invalido (campo obrigatorio faltando, vagasDisponiveis
 * negativo, etc.). Herda a politica de retry de EventoDominioInvalidoException - RabbitMqConfig
 * classifica pela classe-base, nao por esta classe concreta (ver AD-001).
 */
public class EventoInvalidoException extends EventoDominioInvalidoException {

    public EventoInvalidoException(String message) {
        super(message);
    }
}
