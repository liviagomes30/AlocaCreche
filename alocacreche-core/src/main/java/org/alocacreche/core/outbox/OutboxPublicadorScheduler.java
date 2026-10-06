package org.alocacreche.core.outbox;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Scheduled(fixedDelay = 1000)
    public void executar() {
        // TODO: sem teto de tempo por ciclo (ate lote x timeout de confirmacao) e sem recuo para evento devolvido por falta de fila; exige coluna de tentativas na tabela outbox
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
