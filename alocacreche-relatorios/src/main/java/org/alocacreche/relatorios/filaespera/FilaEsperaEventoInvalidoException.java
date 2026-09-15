package org.alocacreche.relatorios.filaespera;

import org.alocacreche.relatorios.evento.EventoDominioInvalidoException;

/**
 * Sinaliza payload do evento FilaEsperaAtualizada invalido (campo obrigatorio faltando, dataSaida
 * anterior a dataEntrada, etc.). Herda a politica de retry de EventoDominioInvalidoException -
 * RabbitMqConfig classifica pela classe-base, nao por esta classe concreta (ver AD-001).
 */
public class FilaEsperaEventoInvalidoException extends EventoDominioInvalidoException {

    public FilaEsperaEventoInvalidoException(String message) {
        super(message);
    }
}
