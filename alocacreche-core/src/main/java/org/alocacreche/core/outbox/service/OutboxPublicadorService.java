package org.alocacreche.core.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Teto da espera entre tentativas de um evento que o broker nao aceitou. */
    private static final long TETO_RECUO_MS = 5_000;

    private static final String CAMPO_EVENT_ID = "eventId";

    /** Evento enviado ao broker que ainda aguarda a confirmacao (ack) de recebimento. */
    private record EnvioPendente(OutboxEvent evento, CorrelationData correlacao) {}

    /** Quantas tentativas seguidas falharam e a partir de quando o evento pode ser tentado de novo. */
    private record Recuo(int tentativas, Instant proximaTentativa) {}

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmacaoTimeoutMs;

    /**
     * Recuo exponencial em memoria (1 s, 2 s, 4 s, ate o teto): evita reenviar a cada ciclo, e encher o
     * log, um evento que o broker recusa. Se o processo reinicia, o recuo zera, o que e inofensivo.
     */
    private final Map<Long, Recuo> recuos = new ConcurrentHashMap<>();

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

    /**
     * Um ciclo completo: trava as linhas pendentes (outras instancias pulam as ja travadas), envia o
     * lote, espera as confirmacoes e marca como PROCESSADO. Tudo numa transacao so: se esta instancia
     * cair no meio, o lock some e outra instancia assume os eventos.
     */
    @Transactional
    public void processarPendentes() {
        publicarLote(
                repository.buscarPendentesParaPublicar(
                        OutboxEvent.STATUS_PENDENTE, PageRequest.of(0, TAMANHO_LOTE)));
    }

    @Transactional
    public void publicarEvento(OutboxEvent evento) {
        publicarLote(List.of(evento));
    }

    /**
     * Reenvia, sem mudar o status, o evento vaga.liberada mais antigo da turma. E o "evento atrasado ou
     * duplicado" da demonstracao: o consumidor deve ignora-lo. Devolve o id do evento reenviado.
     */
    @Transactional(readOnly = true)
    public Optional<Long> reenviarEventoAntigoDaTurma(Long turmaId) {
        Optional<OutboxEvent> antigo = repository.buscarVagaLiberadaMaisAntigaDaTurma(turmaId);
        antigo.ifPresent(evento -> {
            EnvioPendente envio = enviar(evento);
            if (envio != null) {
                aceitoPorUmaFila(evento, envio.correlacao(), confirmacaoTimeoutMs);
            }
        });
        return antigo.map(OutboxEvent::getId);
    }

    /**
     * Envia todos os eventos primeiro e so depois espera as confirmacoes, todas contra o mesmo prazo:
     * o ciclo custa no maximo um timeout, e nao um timeout por evento.
     */
    private void publicarLote(List<OutboxEvent> eventos) {
        Instant agora = Instant.now();
        List<EnvioPendente> enviados = new ArrayList<>();
        for (OutboxEvent evento : eventos) {
            if (!podeTentarAgora(evento, agora)) {
                continue;
            }
            EnvioPendente envio = enviar(evento);
            if (envio != null) {
                enviados.add(envio);
            }
        }

        long prazoNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(confirmacaoTimeoutMs);
        for (EnvioPendente envio : enviados) {
            long restanteMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(prazoNanos - System.nanoTime()));
            if (aceitoPorUmaFila(envio.evento(), envio.correlacao(), restanteMs)) {
                envio.evento().marcarProcessado();
                repository.save(envio.evento());
                esquecerRecuo(envio.evento());
            } else {
                registrarFalha(envio.evento());
            }
        }
    }

    /** Valida e envia; devolve null quando o evento foi descartado (ERRO) ou o envio falhou. */
    private EnvioPendente enviar(OutboxEvent evento) {
        if (evento.getTipoEvento() == null
                || !OutboxRabbitConfig.ROUTING_KEYS_CONHECIDAS.contains(evento.getTipoEvento())) {
            log.warn(
                    "OutboxEvent id={} com tipoEvento sem routing key conhecida: {}",
                    evento.getId(),
                    evento.getTipoEvento());
            descartarComoErro(evento);
            return null;
        }

        String payloadComEventId;
        try {
            payloadComEventId = comEventId(evento);
        } catch (JsonProcessingException e) {
            log.warn("OutboxEvent id={} com payload que nao e um objeto JSON valido", evento.getId());
            descartarComoErro(evento);
            return null;
        }

        try {
            CorrelationData correlacao = new CorrelationData(String.valueOf(evento.getId()));
            rabbitTemplate.send(
                    OutboxRabbitConfig.DOMINIO_EXCHANGE,
                    evento.getTipoEvento(),
                    mensagemJson(payloadComEventId),
                    correlacao);
            return new EnvioPendente(evento, correlacao);
        } catch (AmqpException e) {
            log.error(
                    "Falha ao publicar OutboxEvent id={}, permanece PENDENTE para nova tentativa",
                    evento.getId(),
                    e);
            registrarFalha(evento);
            return null;
        }
    }

    private boolean podeTentarAgora(OutboxEvent evento, Instant agora) {
        if (evento.getId() == null) {
            return true;
        }
        Recuo recuo = recuos.get(evento.getId());
        return recuo == null || !agora.isBefore(recuo.proximaTentativa());
    }

    private void registrarFalha(OutboxEvent evento) {
        if (evento.getId() == null) {
            return;
        }
        recuos.merge(
                evento.getId(),
                new Recuo(1, Instant.now().plus(Duration.ofMillis(recuoMs(1)))),
                (anterior, nova) -> {
                    int tentativas = anterior.tentativas() + 1;
                    return new Recuo(tentativas, Instant.now().plus(Duration.ofMillis(recuoMs(tentativas))));
                });
    }

    private static long recuoMs(int tentativas) {
        return Math.min(TETO_RECUO_MS, 1_000L << Math.min(tentativas - 1, 10));
    }

    private void esquecerRecuo(OutboxEvent evento) {
        if (evento.getId() != null) {
            recuos.remove(evento.getId());
        }
    }

    private boolean aceitoPorUmaFila(OutboxEvent evento, CorrelationData correlacao, long esperaMs) {
        try {
            CorrelationData.Confirm confirmacao =
                    correlacao.getFuture().get(esperaMs, TimeUnit.MILLISECONDS);
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
        esquecerRecuo(evento);
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
