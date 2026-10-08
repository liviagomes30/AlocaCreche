package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.InscricaoRepository;
import org.alocacreche.core.inscricao.StatusInscricao;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expira uma matricula PENDENTE com prazo vencido, numa transacao propria por matricula: um
 * conflito de versao numa turma nao desfaz as outras expiracoes da rodada. Trava a matricula com o
 * mesmo lock da confirmacao, entao confirmar e expirar a mesma matricula nunca acontecem juntos -
 * quem chegar depois ve o status ja alterado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiracaoMatriculaTransacional {

    private final EntityManager entityManager;
    private final MatriculaRepository matriculaRepository;
    private final InscricaoRepository inscricaoRepository;
    private final LiberacaoVaga liberacaoVaga;

    /**
     * @return {@code false} quando a matricula ja nao esta PENDENTE e vencida (confirmada, cancelada
     *     ou expirada entre a varredura e esta transacao) - nada e alterado.
     */
    @Transactional
    public boolean expirar(Long municipioId, Long matriculaId) {
        TenantContext.aplicar(entityManager, municipioId);

        Matricula matricula = matriculaRepository.buscarParaConfirmar(matriculaId).orElse(null);
        LocalDateTime agora = LocalDateTime.now();
        if (matricula == null
                || matricula.getStatus() != StatusMatricula.PENDENTE
                || !agora.isAfter(matricula.getPrazoConfirmacao())) {
            return false;
        }
        Inscricao inscricao =
                inscricaoRepository
                        .findById(matricula.getInscricaoId())
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Inscricao nao encontrada"));

        liberacaoVaga.liberar(municipioId, matricula, StatusMatricula.EXPIRADA, agora);
        inscricao.setStatus(StatusInscricao.EXPIRADA);

        log.info("Matricula {} do municipio {} expirada e vaga devolvida", matriculaId, municipioId);
        return true;
    }
}
