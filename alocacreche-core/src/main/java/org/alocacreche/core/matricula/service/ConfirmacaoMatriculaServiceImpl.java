package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.service.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.InscricaoRepository;
import org.alocacreche.core.inscricao.StatusInscricao;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.outbox.service.OutboxEventWriter;
import org.alocacreche.core.outbox.dto.OutboxPayloads.MatriculaAtualizadaPayload;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmacaoMatriculaServiceImpl implements ConfirmacaoMatriculaService {

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final MatriculaRepository matriculaRepository;
    private final InscricaoRepository inscricaoRepository;
    private final OutboxEventWriter outboxEventWriter;

    @Override
    @Transactional
    public MatriculaConfirmadaResponse confirmar(Long matriculaId) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);

        Matricula matricula =
                matriculaRepository
                        .buscarParaConfirmar(matriculaId)
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Matricula nao encontrada"));
        LocalDateTime agora = LocalDateTime.now();
        validarConfirmavel(matricula, agora);
        Inscricao inscricao =
                inscricaoRepository
                        .findById(matricula.getInscricaoId())
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Inscricao nao encontrada"));

        matricula.setStatus(StatusMatricula.CONFIRMADA);
        matricula.setDataConfirmacao(agora);
        matriculaRepository.saveAndFlush(matricula);
        inscricao.setStatus(StatusInscricao.MATRICULADA);

        Turma turma = matricula.getTurma();
        outboxEventWriter.registrar(
                OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY,
                new MatriculaAtualizadaPayload(
                        OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY,
                        municipioId,
                        turma.getId(),
                        turma.getCreche().getId(),
                        matricula.getInscricaoId(),
                        matricula.getId(),
                        matricula.getStatus().name(),
                        matricula.getDistanciaKm(),
                        matricula.getDataConvocacao().toLocalDate(),
                        agora.toLocalDate(),
                        agora));

        log.info("Matricula {} confirmada no municipio {}", matriculaId, municipioId);
        return new MatriculaConfirmadaResponse(matricula.getId());
    }

    private void validarConfirmavel(Matricula matricula, LocalDateTime agora) {
        if (matricula.getStatus() != StatusMatricula.PENDENTE) {
            throw new ConflitoException("Matricula nao esta pendente de confirmacao");
        }
        if (agora.isAfter(matricula.getPrazoConfirmacao())) {
            throw new ConflitoException("Prazo de confirmacao da matricula vencido");
        }
    }
}
