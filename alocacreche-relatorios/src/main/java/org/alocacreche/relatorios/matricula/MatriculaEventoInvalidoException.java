package org.alocacreche.relatorios.matricula;

import org.alocacreche.relatorios.evento.EventoDominioInvalidoException;

/**
 * Sinaliza payload do evento MatriculaAtualizada invalido (campo obrigatorio faltando, distanciaKm
 * negativo, dataConfirmacao inconsistente com status, etc.). Herda a politica de retry de
 * EventoDominioInvalidoException - RabbitMqConfig classifica pela classe-base, nao por esta classe
 * concreta (ver .specs/STATE.md AD-001).
 */
public class MatriculaEventoInvalidoException extends EventoDominioInvalidoException {

    public MatriculaEventoInvalidoException(String message) {
        super(message);
    }
}
