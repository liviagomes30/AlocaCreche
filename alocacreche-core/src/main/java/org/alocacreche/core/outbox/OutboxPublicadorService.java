package org.alocacreche.core.outbox;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Le eventos PENDENTE (ordenados por id) e publica um por vez no RabbitMQ - falha de um evento
 * nunca aborta os demais do mesmo poll (ver OutboxPublicadorScheduler, que faz o loop). O payload
 * ja e a String JSON pronta gravada por quem escreveu na Outbox - este service nunca desserializa,
 * so repassa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublicadorService {

    private static final Set<String> ROUTING_KEYS_CONHECIDAS =
            Set.of(
                    OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                    OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY,
                    OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public List<OutboxEvent> buscarPendentes() {
        return repository.findByStatusOrderByIdAsc("PENDENTE");
    }

    /**
     * tipoEvento eh a propria routing key (convencao do projeto - ver spec de
     * outbox-publicador-rabbitmq). Falha de publish (AmqpException) fica so em log, sem relancar -
     * o evento continua PENDENTE, sera reprocessado no proximo poll.
     */
    @Transactional
    public void publicarEvento(OutboxEvent evento) {
        if (evento.getTipoEvento() == null
                || !ROUTING_KEYS_CONHECIDAS.contains(evento.getTipoEvento())) {
            log.warn(
                    "OutboxEvent id={} com tipoEvento sem routing key conhecida: {}",
                    evento.getId(),
                    evento.getTipoEvento());
            evento.marcarErro();
            repository.save(evento);
            return;
        }

        try {
            rabbitTemplate.convertAndSend(
                    OutboxRabbitConfig.DOMINIO_EXCHANGE, evento.getTipoEvento(), evento.getPayload());
            evento.marcarProcessado();
            repository.save(evento);
        } catch (AmqpException e) {
            log.error(
                    "Falha ao publicar OutboxEvent id={}, permanece PENDENTE para nova tentativa",
                    evento.getId(),
                    e);
        }
    }
}
