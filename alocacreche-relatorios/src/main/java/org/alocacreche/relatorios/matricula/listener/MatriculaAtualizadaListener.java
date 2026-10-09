package org.alocacreche.relatorios.matricula.listener;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.relatorios.config.RabbitMqConfig;
import org.alocacreche.relatorios.matricula.domain.StatusMatricula;
import org.alocacreche.relatorios.matricula.event.MatriculaAtualizadaEvent;
import org.alocacreche.relatorios.matricula.exception.MatriculaEventoInvalidoException;
import org.alocacreche.relatorios.matricula.repository.MatriculaRelatorioRepository;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatriculaAtualizadaListener {

    private final MatriculaRelatorioRepository repository;
    private final EntityManager entityManager;

    @RabbitListener(queues = RabbitMqConfig.MATRICULA_QUEUE)
    @Transactional
    public void receber(MatriculaAtualizadaEvent evento) {
        validar(evento);

        TenantContext.aplicar(entityManager, evento.municipioId());

        repository.upsert(
                evento.matriculaId(),
                evento.inscricaoId(),
                evento.turmaId(),
                evento.crecheId(),
                evento.municipioId(),
                evento.status().name(),
                evento.distanciaKm(),
                evento.dataConvocacao(),
                evento.dataConfirmacao(),
                evento.eventId());

        log.info(
                "Matricula atualizada: matriculaId={}, status={}, municipioId={}, eventId={}",
                evento.matriculaId(),
                evento.status(),
                evento.municipioId(),
                evento.eventId());
    }

    private void validar(MatriculaAtualizadaEvent evento) {
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
        if (evento.matriculaId() == null) {
            throw rejeitar(evento, "matriculaId ausente");
        }
        if (evento.status() == null) {
            throw rejeitar(evento, "status ausente");
        }
        if (evento.distanciaKm() == null) {
            throw rejeitar(evento, "distanciaKm ausente");
        }
        if (evento.distanciaKm() < 0) {
            throw rejeitar(evento, "distanciaKm negativa");
        }
        if (evento.dataConvocacao() == null) {
            throw rejeitar(evento, "dataConvocacao ausente");
        }
        if (evento.status() == StatusMatricula.CONFIRMADA && evento.dataConfirmacao() == null) {
            throw rejeitar(evento, "status CONFIRMADA sem dataConfirmacao");
        }
        if (evento.status() != StatusMatricula.CONFIRMADA && evento.dataConfirmacao() != null) {
            throw rejeitar(evento, "dataConfirmacao presente com status diferente de CONFIRMADA");
        }
        if (evento.dataConfirmacao() != null && evento.dataConfirmacao().isBefore(evento.dataConvocacao())) {
            throw rejeitar(evento, "dataConfirmacao anterior a dataConvocacao");
        }
    }

    private MatriculaEventoInvalidoException rejeitar(MatriculaAtualizadaEvent evento, String motivo) {
        log.warn("Evento MatriculaAtualizada rejeitado (matriculaId={}): {}", evento.matriculaId(), motivo);
        return new MatriculaEventoInvalidoException(motivo);
    }
}
