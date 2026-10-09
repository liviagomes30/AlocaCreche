package org.alocacreche.core.matricula.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.outbox.service.OutboxEventWriter;
import org.alocacreche.core.outbox.dto.OutboxPayloads.MatriculaAtualizadaPayload;
import org.alocacreche.core.outbox.dto.OutboxPayloads.VagaLiberadaPayload;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encerra uma matricula que ocupava vaga (desistencia ou prazo vencido) e devolve a vaga a turma,
 * com o {@code @Version} de {@link Turma}, gravando {@code vaga.liberada} e
 * {@code matricula.atualizada} na Outbox. MANDATORY: so roda dentro da transacao de quem chama,
 * para a vaga devolvida, o novo status e os eventos serem uma unica escrita.
 */
@Component
@RequiredArgsConstructor
public class LiberacaoVaga {

    private final TurmaRepository turmaRepository;
    private final MatriculaRepository matriculaRepository;
    private final OutboxEventWriter outboxEventWriter;

    @Transactional(propagation = Propagation.MANDATORY)
    public Turma liberar(Long municipioId, Matricula matricula, StatusMatricula novoStatus, LocalDateTime agora) {
        Turma turma = matricula.getTurma();
        if (turma.getVagasDisponiveis() >= turma.getVagasTotais()) {
            throw new ConflitoException("Turma ja esta com todas as vagas livres");
        }

        turma.setVagasDisponiveis(turma.getVagasDisponiveis() + 1);
        turmaRepository.saveAndFlush(turma);

        matricula.setStatus(novoStatus);
        matricula.setDataConfirmacao(null);
        matriculaRepository.saveAndFlush(matricula);

        registrarEventos(municipioId, turma, matricula, agora);
        return turma;
    }

    private void registrarEventos(Long municipioId, Turma turma, Matricula matricula, LocalDateTime agora) {
        Long crecheId = turma.getCreche().getId();
        outboxEventWriter.registrar(
                OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                new VagaLiberadaPayload(
                        OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY,
                        municipioId,
                        turma.getId(),
                        crecheId,
                        turma.getVagasDisponiveis(),
                        agora));
        outboxEventWriter.registrar(
                OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY,
                new MatriculaAtualizadaPayload(
                        OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY,
                        municipioId,
                        turma.getId(),
                        crecheId,
                        matricula.getInscricaoId(),
                        matricula.getId(),
                        matricula.getStatus().name(),
                        matricula.getDistanciaKm(),
                        matricula.getDataConvocacao().toLocalDate(),
                        null,
                        agora));
    }
}
