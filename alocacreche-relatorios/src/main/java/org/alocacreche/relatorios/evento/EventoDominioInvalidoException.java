package org.alocacreche.relatorios.evento;

/**
 * Classe-base para excecoes de payload de evento de dominio invalido (campo obrigatorio faltando,
 * valor fora da regra de negocio, etc.), comuns a qualquer consumidor RabbitMQ deste modulo.
 *
 * <p>RabbitMqConfig classifica esta classe-base (nao uma excecao concreta de um dominio especifico)
 * como nao-retryable - rejeicao imediata para a dead-letter queue, sem reentrega (ver AD-001). Um
 * consumidor futuro (ex.: MatriculaCriada) so precisa lancar uma subclasse desta excecao para herdar
 * a mesma politica, sem editar RabbitMqConfig.
 */
public abstract class EventoDominioInvalidoException extends RuntimeException {

    protected EventoDominioInvalidoException(String message) {
        super(message);
    }
}
