package org.alocacreche.relatorios.ocupacao;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.config.RabbitMqConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consome VagaLiberada da fila declarada em RabbitMqConfig. Valida o payload (lanca
 * EventoInvalidoException se invalido - exclui da politica de retry, ver AD-001), aplica o tenant
 * da sessao via SET LOCAL, e delega o upsert idempotente ao repositorio - tudo na mesma transacao
 * (OCUP-01/04).
 */
@Component
@RequiredArgsConstructor
public class VagaLiberadaListener {

    private final OcupacaoTurmaRepository repository;
    private final EntityManager entityManager;

    @RabbitListener(queues = RabbitMqConfig.OCUPACAO_QUEUE)
    @Transactional
    public void receber(VagaLiberadaEvent evento) {
        validar(evento);

        entityManager
                .createNativeQuery("SET LOCAL app.current_tenant = '" + evento.municipioId() + "'")
                .executeUpdate();

        repository.upsert(
                evento.turmaId(),
                evento.municipioId(),
                evento.crecheId(),
                evento.vagasDisponiveis(),
                evento.eventId());
    }

    private void validar(VagaLiberadaEvent evento) {
        if (evento.eventId() == null) {
            throw new EventoInvalidoException("eventId ausente");
        }
        if (evento.municipioId() == null) {
            throw new EventoInvalidoException("municipioId ausente");
        }
        if (evento.turmaId() == null) {
            throw new EventoInvalidoException("turmaId ausente");
        }
        if (evento.vagasDisponiveis() == null) {
            throw new EventoInvalidoException("vagasDisponiveis ausente");
        }
        if (evento.vagasDisponiveis() < 0) {
            throw new EventoInvalidoException(
                    "vagasDisponiveis negativo: " + evento.vagasDisponiveis());
        }
    }
}
