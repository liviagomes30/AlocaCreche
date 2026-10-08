package org.alocacreche.core.matricula.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.springframework.stereotype.Component;

/**
 * Uma rodada de manutencao num municipio: primeiro {@code verificarPrazosVencidos} (expira e
 * devolve vagas), depois {@code reconciliarVagasDisponiveis} (aloca em toda turma com vaga livre e
 * fila nao vazia - inclusive as vagas que a expiracao acabou de devolver e as que uma desistencia
 * nao conseguiu realocar). Sem transacao propria: cada passo chama um bean injetado que abre a
 * sua, com o {@code SET LOCAL} do municipio (self-invocation, docs/arquitetura/notas-tecnicas.md).
 * A falha de uma matricula ou turma e registrada e nao impede as demais.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManutencaoMatriculaPorMunicipio {

    private final ManutencaoMatriculaConsulta consulta;
    private final ExpiracaoMatriculaTransacional expiracao;
    private final AlocacaoVagaService alocacaoVagaService;

    public void executar(Long municipioId) {
        verificarPrazosVencidos(municipioId);
        reconciliarVagasDisponiveis(municipioId);
    }

    void verificarPrazosVencidos(Long municipioId) {
        for (Long matriculaId : consulta.matriculasComPrazoVencido(municipioId)) {
            try {
                expiracao.expirar(municipioId, matriculaId);
            } catch (RuntimeException excecao) {
                log.error(
                        "Falha ao expirar a matricula {} do municipio {}; nova tentativa na proxima rodada",
                        matriculaId,
                        municipioId,
                        excecao);
            }
        }
    }

    void reconciliarVagasDisponiveis(Long municipioId) {
        for (Long turmaId : consulta.turmasComVagaEFila(municipioId)) {
            preencherVagas(municipioId, turmaId);
        }
    }

    /**
     * Aloca ate acabar a vaga ou a fila: cada alocacao bem-sucedida consome uma vaga, entao o laco
     * termina.
     */
    private void preencherVagas(Long municipioId, Long turmaId) {
        while (true) {
            try {
                MatriculaAlocadaResponse alocada =
                        alocacaoVagaService.alocarProximoElegivelNoMunicipio(municipioId, turmaId);
                log.info(
                        "Reconciliacao alocou a matricula {} na turma {} do municipio {}",
                        alocada.matriculaId(),
                        turmaId,
                        municipioId);
            } catch (ConflitoException excecao) {
                return;
            } catch (RuntimeException excecao) {
                log.error(
                        "Falha ao reconciliar a turma {} do municipio {}; nova tentativa na proxima rodada",
                        turmaId,
                        municipioId,
                        excecao);
                return;
            }
        }
    }
}
