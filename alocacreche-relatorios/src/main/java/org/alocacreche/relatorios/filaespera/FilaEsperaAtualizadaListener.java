package org.alocacreche.relatorios.filaespera;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.relatorios.config.RabbitMqConfig;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consome FilaEsperaAtualizada da fila declarada em RabbitMqConfig. Valida o payload (campos
 * obrigatorios e consistencia dataSaida/dataEntrada - lanca FilaEsperaEventoInvalidoException se
 * invalido, exclui da politica de retry, ver AD-001), aplica o tenant da sessao via TenantContext,
 * e delega o upsert idempotente ao repositorio - tudo na mesma transacao (FILA-01/04/07/08).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilaEsperaAtualizadaListener {

    private final FilaEsperaInscricaoRepository repository;
    private final EntityManager entityManager;

    @RabbitListener(queues = RabbitMqConfig.FILA_ESPERA_QUEUE)
    @Transactional
    public void receber(FilaEsperaAtualizadaEvent evento) {
        validar(evento);

        TenantContext.aplicar(entityManager, evento.municipioId());

        repository.upsert(
                evento.inscricaoId(),
                evento.turmaId(),
                evento.crecheId(),
                evento.municipioId(),
                evento.dataEntrada(),
                evento.dataSaida(),
                evento.eventId());

        log.info(
                "Fila de espera atualizada: inscricaoId={}, turmaId={}, municipioId={}, eventId={}",
                evento.inscricaoId(),
                evento.turmaId(),
                evento.municipioId(),
                evento.eventId());
    }

    private void validar(FilaEsperaAtualizadaEvent evento) {
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
        if (evento.inscricaoId() == null) {
            throw rejeitar(evento, "inscricaoId ausente");
        }
        if (evento.dataEntrada() == null) {
            throw rejeitar(evento, "dataEntrada ausente");
        }
        if (evento.dataSaida() != null && evento.dataSaida().isBefore(evento.dataEntrada())) {
            throw rejeitar(evento, "dataSaida anterior a dataEntrada");
        }
    }

    /**
     * Retorna a excecao em vez de lanca-la diretamente para que cada chamada em validar() use
     * "throw rejeitar(...)" - mesma convencao de VagaLiberadaListener.
     */
    private FilaEsperaEventoInvalidoException rejeitar(FilaEsperaAtualizadaEvent evento, String motivo) {
        log.warn("Evento FilaEsperaAtualizada rejeitado (inscricaoId={}): {}", evento.inscricaoId(), motivo);
        return new FilaEsperaEventoInvalidoException(motivo);
    }
}
