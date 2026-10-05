package org.alocacreche.core.matricula.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlocacaoVagaService {

    static final int MAXIMO_TENTATIVAS = 3;

    private final AlocacaoVagaTransacional alocacaoVagaTransacional;

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAXIMO_TENTATIVAS,
            backoff = @Backoff(delay = 20, multiplier = 2))
    public MatriculaAlocadaResponse alocarProximoElegivel(Long turmaId) {
        return alocacaoVagaTransacional.executar(turmaId);
    }

    @Recover
    MatriculaAlocadaResponse esgotarTentativas(OptimisticLockingFailureException causa, Long turmaId) {
        log.warn("Alocacao da turma {} abandonada apos {} conflitos de versao", turmaId, MAXIMO_TENTATIVAS);
        throw new ConflitoException("Turma alterada por outra alocacao simultanea, tente novamente", causa);
    }

    @Recover
    MatriculaAlocadaResponse repassarFalhaDeNegocio(RuntimeException causa, Long turmaId) {
        throw causa;
    }
}
