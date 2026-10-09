package org.alocacreche.relatorios.ocupacao.listener;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.relatorios.config.RabbitMqConfig;
import org.alocacreche.relatorios.ocupacao.event.VagaLiberadaEvent;
import org.alocacreche.relatorios.ocupacao.exception.EventoInvalidoException;
import org.alocacreche.relatorios.ocupacao.repository.OcupacaoTurmaRepository;
import org.alocacreche.relatorios.ocupacao.service.PainelPublicoCacheInvalidador;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class VagaLiberadaListener {

    private final OcupacaoTurmaRepository repository;
    private final EntityManager entityManager;
    private final PainelPublicoCacheInvalidador painelCacheInvalidador;

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
        painelCacheInvalidador.invalidarAposCommit(evento.municipioId());

        log.info(
                "Ocupacao atualizada: turmaId={}, municipioId={}, eventId={}",
                evento.turmaId(),
                evento.municipioId(),
                evento.eventId());
    }

    private void validar(VagaLiberadaEvent evento) {
        if (evento.eventId() == null) {
            throw rejeitar(evento, "eventId ausente");
        }
        if (evento.municipioId() == null) {
            throw rejeitar(evento, "municipioId ausente");
        }
        if (evento.turmaId() == null) {
            throw rejeitar(evento, "turmaId ausente");
        }
        if (evento.crecheId() == null) {
            throw rejeitar(evento, "crecheId ausente");
        }
        if (evento.vagasDisponiveis() == null) {
            throw rejeitar(evento, "vagasDisponiveis ausente");
        }
        if (evento.vagasDisponiveis() < 0) {
            throw rejeitar(evento, "vagasDisponiveis negativo: " + evento.vagasDisponiveis());
        }
    }

    private EventoInvalidoException rejeitar(VagaLiberadaEvent evento, String motivo) {
        log.warn("Evento VagaLiberada rejeitado (turmaId={}): {}", evento.turmaId(), motivo);
        return new EventoInvalidoException(motivo);
    }
}
