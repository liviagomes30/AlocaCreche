package org.alocacreche.relatorios.evento.exception;

public abstract class EventoDominioInvalidoException extends RuntimeException {

    protected EventoDominioInvalidoException(String message) {
        super(message);
    }
}
