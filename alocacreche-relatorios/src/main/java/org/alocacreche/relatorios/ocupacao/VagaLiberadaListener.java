package org.alocacreche.relatorios.ocupacao;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.relatorios.config.RabbitMqConfig;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consome VagaLiberada da fila declarada em RabbitMqConfig. Valida o payload (lanca
 * EventoInvalidoException se invalido - exclui da politica de retry, ver AD-001), aplica o tenant
 * da sessao via TenantContext, e delega o upsert idempotente ao repositorio - tudo na mesma
 * transacao (OCUP-01/04).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VagaLiberadaListener {

    private final OcupacaoTurmaRepository repository;
    private final EntityManager entityManager;

    @RabbitListener(queues = RabbitMqConfig.OCUPACAO_QUEUE)
    @Transactional
    public void receber(VagaLiberadaEvent evento) {
        validar(evento);

        TenantContext.aplicar(entityManager, evento.municipioId());

        repository.upsert(
                evento.turmaId(),
                evento.municipioId(),
                evento.crecheId(),
                evento.vagasDisponiveis(),
                evento.eventId());

        log.info(
                "Ocupacao atualizada: turmaId={}, municipioId={}, eventId={}",
                evento.turmaId(),
                evento.municipioId(),
                evento.eventId());
    }

    private void validar(VagaLiberadaEvent evento) {
        if (evento.eventId() == null) {
            rejeitar(evento, "eventId ausente");
        }
        if (evento.municipioId() == null) {
            rejeitar(evento, "municipioId ausente");
        }
        if (evento.turmaId() == null) {
            rejeitar(evento, "turmaId ausente");
        }
        if (evento.crecheId() == null) {
            rejeitar(evento, "crecheId ausente");
        }
        if (evento.vagasDisponiveis() == null) {
            rejeitar(evento, "vagasDisponiveis ausente");
        }
        if (evento.vagasDisponiveis() < 0) {
            rejeitar(evento, "vagasDisponiveis negativo: " + evento.vagasDisponiveis());
        }
    }

    private void rejeitar(VagaLiberadaEvent evento, String motivo) {
        log.warn("Evento VagaLiberada rejeitado (turmaId={}): {}", evento.turmaId(), motivo);
        throw new EventoInvalidoException(motivo);
    }
}
