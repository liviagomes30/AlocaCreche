package org.alocacreche.relatorios.evento;

public abstract class EventoDominioInvalidoException extends RuntimeException {

    protected EventoDominioInvalidoException(String message) {
        super(message);
    }
}
