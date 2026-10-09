package org.alocacreche.core.outbox.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.outbox.service.OutboxPurgaService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPurgaScheduler {

    private final OutboxPurgaService purgaService;

    @Scheduled(cron = "${alocacreche.outbox.purga.cron:0 0 3 * * *}")
    public void executar() {
        try {
            purgaService.purgarProcessadosAntigos();
        } catch (RuntimeException e) {
            log.error("Falha na purga da Outbox - sera tentada de novo na proxima execucao", e);
        }
    }
}
