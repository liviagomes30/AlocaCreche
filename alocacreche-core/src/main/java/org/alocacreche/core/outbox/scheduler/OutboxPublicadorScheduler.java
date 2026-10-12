package org.alocacreche.core.outbox.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.outbox.service.OutboxPublicadorControle;
import org.alocacreche.core.outbox.service.OutboxPublicadorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "alocacreche.outbox.publicador.habilitado",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxPublicadorScheduler {

    private final OutboxPublicadorService outboxPublicadorService;
    private final OutboxPublicadorControle controle;

    /**
     * A transacao (e o lock das linhas) vive em {@code processarPendentes}, num bean injetado: chamar
     * um metodo {@code @Transactional} por {@code this} nao abriria transacao.
     */
    @Scheduled(fixedDelay = 1000)
    public void executar() {
        if (controle.pausado()) {
            return;
        }
        try {
            outboxPublicadorService.processarPendentes();
        } catch (RuntimeException e) {
            log.error("Falha inesperada ao processar a Outbox", e);
        }
    }
}
