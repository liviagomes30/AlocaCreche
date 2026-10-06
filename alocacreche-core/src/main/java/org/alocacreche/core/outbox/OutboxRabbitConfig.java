package org.alocacreche.core.outbox;

import java.util.Set;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OutboxRabbitConfig {

    public static final String DOMINIO_EXCHANGE = "alocacreche.dominio";
    public static final String VAGA_LIBERADA_ROUTING_KEY = "vaga.liberada";
    public static final String FILA_ATUALIZADA_ROUTING_KEY = "fila.atualizada";
    public static final String MATRICULA_ATUALIZADA_ROUTING_KEY = "matricula.atualizada";

    public static final Set<String> ROUTING_KEYS_CONHECIDAS =
            Set.of(
                    VAGA_LIBERADA_ROUTING_KEY,
                    FILA_ATUALIZADA_ROUTING_KEY,
                    MATRICULA_ATUALIZADA_ROUTING_KEY);

    @Bean
    public TopicExchange dominioExchange() {
        return new TopicExchange(DOMINIO_EXCHANGE);
    }
}
