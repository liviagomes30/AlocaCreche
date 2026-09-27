package org.alocacreche.core.outbox;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara o poll periodico e faz o loop de publicacao - o loop mora aqui, nao dentro de
 * OutboxPublicadorService, para que a chamada a publicarEvento seja externa ao bean do service
 * (nunca this.publicarEvento). Se o loop estivesse no proprio service, o proxy do Spring nao
 * interceptaria a chamada interna e o @Transactional por evento nao teria efeito nenhum
 * (self-invocation, ver CLAUDE.md).
 */
@Component
@RequiredArgsConstructor
public class OutboxPublicadorScheduler {

    private final OutboxPublicadorService outboxPublicadorService;

    @Scheduled(fixedDelay = 1000)
    public void executar() {
        List<OutboxEvent> pendentes = outboxPublicadorService.buscarPendentes();
        for (OutboxEvent evento : pendentes) {
            outboxPublicadorService.publicarEvento(evento);
        }
    }
}
