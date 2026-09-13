package org.alocacreche.relatorios.ocupacao;

/**
 * Sinaliza payload de evento invalido (campo obrigatorio faltando, vagasDisponiveis negativo,
 * etc.). Excluida da politica de retry do RabbitMqConfig - rejeicao imediata para a dead-letter
 * queue, sem reentrega.
 */
public class EventoInvalidoException extends RuntimeException {

    public EventoInvalidoException(String message) {
        super(message);
    }
}
