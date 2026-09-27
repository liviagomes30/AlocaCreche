package org.alocacreche.core.outbox;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara o poll periodico e faz o loop de publicacao - o loop mora aqui, nao dentro de
 * OutboxPublicadorService, para que a chamada a publicarEvento seja externa ao bean do service
 * (nunca this.publicarEvento). Se o loop estivesse no proprio service, o proxy do Spring nao
 * interceptaria a chamada interna e o @Transactional por evento nao teria efeito nenhum
 * (self-invocation, ver CLAUDE.md).
 *
 * <p>O try/catch por evento aqui e uma segunda linha de defesa, alem da que ja existe dentro de
 * OutboxPublicadorService#publicarEvento (que so trata AmqpException) - qualquer falha
 * inesperada (ex.: erro de banco ao salvar) nao pode interromper o processamento dos demais
 * eventos do mesmo poll (OUTPUB-06). Descoberto pelo Verificador via mutation testing: sem isso,
 * uma excecao nao prevista de um unico evento aborta o lote inteiro silenciosamente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublicadorScheduler {

    private final OutboxPublicadorService outboxPublicadorService;

    @Scheduled(fixedDelay = 1000)
    public void executar() {
        List<OutboxEvent> pendentes = outboxPublicadorService.buscarPendentes();
        for (OutboxEvent evento : pendentes) {
            try {
                outboxPublicadorService.publicarEvento(evento);
            } catch (RuntimeException e) {
                log.error(
                        "Falha inesperada ao processar OutboxEvent id={}, seguindo para o proximo do"
                                + " mesmo poll",
                        evento.getId(),
                        e);
            }
        }
    }
}
