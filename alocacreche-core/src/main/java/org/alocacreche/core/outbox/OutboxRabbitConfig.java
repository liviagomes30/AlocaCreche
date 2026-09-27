package org.alocacreche.core.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declara a exchange do lado do publicador, de forma idempotente - alocacreche-core nao pode
 * depender de alocacreche-relatorios estar de pe primeiro para a exchange existir no broker.
 * Constantes espelham literalmente
 * alocacreche-relatorios/src/main/java/org/alocacreche/relatorios/config/RabbitMqConfig.java
 * (mesmo nome/tipo - nao ha modulo Java compartilhado entre os dois deploys, ADR 0001 decisao 2).
 */
@Configuration
public class OutboxRabbitConfig {

    public static final String DOMINIO_EXCHANGE = "alocacreche.dominio";
    public static final String VAGA_LIBERADA_ROUTING_KEY = "vaga.liberada";
    public static final String FILA_ATUALIZADA_ROUTING_KEY = "fila.atualizada";
    public static final String MATRICULA_ATUALIZADA_ROUTING_KEY = "matricula.atualizada";

    @Bean
    public TopicExchange dominioExchange() {
        return new TopicExchange(DOMINIO_EXCHANGE);
    }
}
