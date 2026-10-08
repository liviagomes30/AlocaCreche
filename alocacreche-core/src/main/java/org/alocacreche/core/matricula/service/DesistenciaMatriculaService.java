package org.alocacreche.core.matricula.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DesistenciaMatriculaService {

    static final int MAXIMO_TENTATIVAS = 3;

    private final DesistenciaMatriculaTransacional desistenciaMatriculaTransacional;

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAXIMO_TENTATIVAS,
            backoff = @Backoff(delay = 20, multiplier = 2))
    public MatriculaCanceladaResponse desistir(Long matriculaId) {
        return desistenciaMatriculaTransacional.executar(matriculaId);
    }

    @Recover
    MatriculaCanceladaResponse esgotarTentativas(OptimisticLockingFailureException causa, Long matriculaId) {
        log.warn("Desistencia da matricula {} abandonada apos {} conflitos de versao", matriculaId, MAXIMO_TENTATIVAS);
        throw new ConflitoException("Turma alterada por outra operacao simultanea, tente novamente", causa);
    }

    @Recover
    MatriculaCanceladaResponse repassarFalhaDeNegocio(RuntimeException causa, Long matriculaId) {
        throw causa;
    }
}
