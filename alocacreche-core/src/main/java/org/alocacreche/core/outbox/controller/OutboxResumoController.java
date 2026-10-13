package org.alocacreche.core.outbox.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.alocacreche.core.outbox.service.OutboxPublicadorControle;
import org.alocacreche.core.outbox.service.OutboxPublicadorService;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Situacao da Outbox e controles de demonstracao do envio ao RabbitMQ. A tabela outbox nao e isolada
 * por municipio, entao os numeros sao globais: por isso so o ADMIN (curinga) recebe as permissoes,
 * que nao constam nos perfis GESTOR e ATENDENTE. Pausar o envio e reenviar evento antigo so funcionam
 * com {@code alocacreche.outbox.controle-demo.habilitado=true}.
 */
@Slf4j
@RestController
@Tag(name = "Outbox", description = "Situacao dos eventos publicados no RabbitMQ e controles de demonstracao (somente ADMIN).")
@RequestMapping("/api/v1/notificacao/outbox")
@RequiredArgsConstructor
public class OutboxResumoController {

    private static final String FILA_DLQ_OCUPACAO = OutboxRabbitConfig.FILAS_DLQ_RELATORIOS.get(0);

    private static final String ERRO_CONTROLE_DESLIGADO =
            "Controle de demonstracao desligado (alocacreche.outbox.controle-demo.habilitado=false)";

    /**
     * {@code dlqMensagens} e nulo quando o broker nao responde ou nenhuma DLQ existe ainda;
     * {@code jobManutencao} diz se o job de matriculas esta ligado neste core.
     */
    public record ResumoOutbox(
            long pendentes,
            long processados,
            long erros,
            boolean publicadorPausado,
            boolean controleDemo,
            boolean jobManutencao,
            boolean brokerDisponivel,
            Long dlqMensagens) {}

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPublicadorControle controle;
    private final OutboxPublicadorService publicadorService;
    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;

    @Value("${alocacreche.matricula.manutencao.habilitado:true}")
    private boolean jobManutencaoHabilitado;

    @SaCheckPermission("notificacao:outbox:list")
    @GetMapping
    public ResumoOutbox resumo() {
        Optional<Long> dlq = contarMensagensDasDlqs();
        return new ResumoOutbox(
                outboxEventRepository.countByStatus(OutboxEvent.STATUS_PENDENTE),
                outboxEventRepository.countByStatus(OutboxEvent.STATUS_PROCESSADO),
                outboxEventRepository.countByStatus(OutboxEvent.STATUS_ERRO),
                controle.pausado(),
                controle.controleDemoHabilitado(),
                jobManutencaoHabilitado,
                brokerDisponivel(),
                dlq.orElse(null));
    }

    @SaCheckPermission("notificacao:outbox:status")
    @PostMapping("/publicador/pausar")
    public ResponseEntity<Map<String, Object>> pausar() {
        return alterarPausa(true);
    }

    @SaCheckPermission("notificacao:outbox:status")
    @PostMapping("/publicador/retomar")
    public ResponseEntity<Map<String, Object>> retomar() {
        return alterarPausa(false);
    }

    /** Reenvia o vaga.liberada mais antigo da turma: o MSRelatorios deve ignora-lo (sequencia menor). */
    @SaCheckPermission("notificacao:outbox:status")
    @PostMapping("/reenviar-antigo")
    public ResponseEntity<Map<String, Object>> reenviarAntigo(@RequestParam Long turmaId) {
        if (!controle.controleDemoHabilitado()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ERRO_CONTROLE_DESLIGADO));
        }
        Optional<Long> eventId = publicadorService.reenviarEventoAntigoDaTurma(turmaId);
        if (eventId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("erro", "Nenhum evento vaga.liberada processado para a turma " + turmaId));
        }
        return ResponseEntity.ok(Map.of("eventId", eventId.get(), "turmaId", turmaId));
    }

    /**
     * Publica no exchange do dominio um vaga.liberada sem turmaId: o MSRelatorios o rejeita e ele cai na fila de
     * rejeicao (DLQ) de ocupacao. E a "mensagem invalida" da demonstracao, sem passar pela Outbox.
     */
    @SaCheckPermission("notificacao:outbox:status")
    @PostMapping("/dlq/provocar")
    public ResponseEntity<Map<String, Object>> provocarMensagemInvalida() {
        if (!controle.controleDemoHabilitado()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ERRO_CONTROLE_DESLIGADO));
        }
        Message mensagem = MessageBuilder.withBody("{\"eventId\":999999,\"municipioId\":1}".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        try {
            rabbitTemplate.send(OutboxRabbitConfig.DOMINIO_EXCHANGE, OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY, mensagem);
        } catch (AmqpException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("erro", "RabbitMQ indisponivel"));
        }
        return ResponseEntity.ok(Map.of("publicada", true, "filaDeRejeicao", FILA_DLQ_OCUPACAO));
    }

    /** Esvazia so a DLQ de ocupacao: tira a mensagem invalida da demonstracao depois de mostra-la. */
    @SaCheckPermission("notificacao:outbox:status")
    @PostMapping("/dlq/limpar")
    public ResponseEntity<Map<String, Object>> limparDlqDeOcupacao() {
        if (!controle.controleDemoHabilitado()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ERRO_CONTROLE_DESLIGADO));
        }
        try {
            return ResponseEntity.ok(Map.of("removidas", amqpAdmin.purgeQueue(FILA_DLQ_OCUPACAO)));
        } catch (AmqpException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("erro", "RabbitMQ indisponivel"));
        }
    }

    private ResponseEntity<Map<String, Object>> alterarPausa(boolean pausar) {
        if (!controle.definirPausado(pausar)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ERRO_CONTROLE_DESLIGADO));
        }
        return ResponseEntity.ok(Map.of("publicadorPausado", controle.pausado()));
    }

    private boolean brokerDisponivel() {
        try {
            amqpAdmin.getQueueInfo(OutboxRabbitConfig.FILAS_DLQ_RELATORIOS.get(0));
            return true;
        } catch (AmqpException e) {
            log.debug("Broker indisponivel ao consultar as DLQs: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private Optional<Long> contarMensagensDasDlqs() {
        try {
            long total = 0;
            boolean algumaExiste = false;
            for (String fila : OutboxRabbitConfig.FILAS_DLQ_RELATORIOS) {
                QueueInformation info = amqpAdmin.getQueueInfo(fila);
                if (info != null) {
                    algumaExiste = true;
                    total += info.getMessageCount();
                }
            }
            return algumaExiste ? Optional.of(total) : Optional.empty();
        } catch (AmqpException e) {
            return Optional.empty();
        }
    }
}
