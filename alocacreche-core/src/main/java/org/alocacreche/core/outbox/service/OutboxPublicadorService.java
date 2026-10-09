package org.alocacreche.core.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OutboxPublicadorService {

    static final int TAMANHO_LOTE = 100;

    private static final String CAMPO_EVENT_ID = "eventId";

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmacaoTimeoutMs;

    public OutboxPublicadorService(
            OutboxEventRepository repository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${alocacreche.outbox.publicador.confirmacao-timeout-ms:5000}")
                    long confirmacaoTimeoutMs) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmacaoTimeoutMs = confirmacaoTimeoutMs;
    }

    public List<OutboxEvent> buscarPendentes() {
        return repository.findByStatusOrderByIdAsc(
                OutboxEvent.STATUS_PENDENTE, PageRequest.of(0, TAMANHO_LOTE));
    }

    @Transactional
    public void publicarEvento(OutboxEvent evento) {
        if (evento.getTipoEvento() == null
                || !OutboxRabbitConfig.ROUTING_KEYS_CONHECIDAS.contains(evento.getTipoEvento())) {
            log.warn(
                    "OutboxEvent id={} com tipoEvento sem routing key conhecida: {}",
                    evento.getId(),
                    evento.getTipoEvento());
            descartarComoErro(evento);
            return;
        }

        String payloadComEventId;
        try {
            payloadComEventId = comEventId(evento);
        } catch (JsonProcessingException e) {
            log.warn("OutboxEvent id={} com payload que nao e um objeto JSON valido", evento.getId());
            descartarComoErro(evento);
            return;
        }

        try {
            CorrelationData correlacao = new CorrelationData(String.valueOf(evento.getId()));
            rabbitTemplate.send(
                    OutboxRabbitConfig.DOMINIO_EXCHANGE,
                    evento.getTipoEvento(),
                    mensagemJson(payloadComEventId),
                    correlacao);
            if (aceitoPorUmaFila(evento, correlacao)) {
                evento.marcarProcessado();
                repository.save(evento);
            }
        } catch (AmqpException e) {
            log.error(
                    "Falha ao publicar OutboxEvent id={}, permanece PENDENTE para nova tentativa",
                    evento.getId(),
                    e);
        }
    }

    private boolean aceitoPorUmaFila(OutboxEvent evento, CorrelationData correlacao) {
        try {
            CorrelationData.Confirm confirmacao =
                    correlacao.getFuture().get(confirmacaoTimeoutMs, TimeUnit.MILLISECONDS);
            if (!confirmacao.isAck()) {
                log.warn(
                        "OutboxEvent id={} recusado (nack) pelo broker, permanece PENDENTE",
                        evento.getId());
                return false;
            }
            if (correlacao.getReturned() != null) {
                log.warn(
                        "OutboxEvent id={} devolvido pelo broker por nao haver fila ligada a routing key, permanece PENDENTE",
                        evento.getId());
                return false;
            }
            return true;
        } catch (TimeoutException e) {
            log.warn(
                    "OutboxEvent id={} sem confirmacao do broker em {} ms, permanece PENDENTE",
                    evento.getId(),
                    confirmacaoTimeoutMs);
        } catch (ExecutionException e) {
            log.warn(
                    "OutboxEvent id={} com confirmacao falha ({}), permanece PENDENTE",
                    evento.getId(),
                    e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "OutboxEvent id={} com espera da confirmacao interrompida, permanece PENDENTE",
                    evento.getId());
        }
        return false;
    }

    private Message mensagemJson(String payload) {
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .build();
    }

    private void descartarComoErro(OutboxEvent evento) {
        evento.marcarErro();
        repository.save(evento);
    }

    private String comEventId(OutboxEvent evento) throws JsonProcessingException {
        JsonNode raiz = objectMapper.readTree(evento.getPayload());
        if (!(raiz instanceof ObjectNode objeto)) {
            throw new JsonMappingException(null, "payload da Outbox nao e um objeto JSON");
        }
        objeto.put(CAMPO_EVENT_ID, evento.getId());
        return objectMapper.writeValueAsString(objeto);
    }
}
