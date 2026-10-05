package org.alocacreche.core.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OutboxEventWriter {

    private static final Set<String> ROUTING_KEYS_CONHECIDAS =
            Set.of(
                    OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                    OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY,
                    OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY);

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(String routingKey, Object payload) {
        if (routingKey == null || !ROUTING_KEYS_CONHECIDAS.contains(routingKey)) {
            throw new IllegalArgumentException("Routing key desconhecida para a Outbox: " + routingKey);
        }
        Objects.requireNonNull(payload, "payload nao pode ser nulo");
        repository.save(new OutboxEvent(routingKey, serializar(payload)));
    }

    private String serializar(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload do evento nao serializavel", e);
        }
    }
}
