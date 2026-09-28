package org.alocacreche.core.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
