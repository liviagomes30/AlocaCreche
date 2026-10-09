package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Set;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Desistencia da matricula: cancelar a matricula e devolver a vaga a turma sao uma unica transacao,
 * com o mesmo lock otimista ({@code @Version} em {@link Turma}) da alocacao - nunca existe vaga
 * devolvida sem matricula cancelada, nem o contrario. O retry fica em
 * {@link DesistenciaMatriculaService}, em outro bean, para cada tentativa abrir transacao nova.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesistenciaMatriculaTransacional {

    private static final Set<StatusMatricula> STATUS_COM_VAGA = Set.of(StatusMatricula.PENDENTE, StatusMatricula.CONFIRMADA);

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final MatriculaRepository matriculaRepository;
    private final InscricaoRepository inscricaoRepository;
    private final LiberacaoVaga liberacaoVaga;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public MatriculaCanceladaResponse executar(Long matriculaId) {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);

        Matricula matricula =
                matriculaRepository
                        .buscarParaConfirmar(matriculaId)
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Matricula nao encontrada"));
        if (!STATUS_COM_VAGA.contains(matricula.getStatus())) {
            throw new ConflitoException("Matricula nao ocupa vaga para ser cancelada");
        }
        Inscricao inscricao =
                inscricaoRepository
                        .findById(matricula.getInscricaoId())
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Inscricao nao encontrada"));

        Turma turma = liberacaoVaga.liberar(municipioId, matricula, StatusMatricula.CANCELADA, LocalDateTime.now());
        inscricao.setStatus(StatusInscricao.CANCELADA);
        eventPublisher.publishEvent(new VagaLiberadaInternalEvent(municipioId, turma.getId()));

        log.info("Matricula {} cancelada e vaga devolvida a turma {} do municipio {}", matriculaId, turma.getId(), municipioId);
        return new MatriculaCanceladaResponse(matricula.getId(), turma.getId(), turma.getVagasDisponiveis());
    }
}
