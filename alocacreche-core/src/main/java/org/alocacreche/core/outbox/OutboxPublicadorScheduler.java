package org.alocacreche.core.outbox;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublicadorScheduler {

    private final OutboxPublicadorService outboxPublicadorService;

    @Scheduled(fixedDelay = 1000)
    public void executar() {
        List<OutboxEvent> pendentes = outboxPublicadorService.buscarPendentes();
        for (OutboxEvent evento : pendentes) {
            publicarIsolandoFalhaInesperada(evento);
        }
    }

    private void publicarIsolandoFalhaInesperada(OutboxEvent evento) {
        try {
            outboxPublicadorService.publicarEvento(evento);
        } catch (RuntimeException e) {
            log.error("Falha inesperada ao processar OutboxEvent id={}", evento.getId(), e);
        }
    }
}
