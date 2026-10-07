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

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAXIMO_TENTATIVAS,
            backoff = @Backoff(delay = 20, multiplier = 2),
            recover = "recuperarNoMunicipio")
    public MatriculaAlocadaResponse alocarProximoElegivelNoMunicipio(Long municipioId, Long turmaId) {
        return alocacaoVagaTransacional.executarNoMunicipio(municipioId, turmaId);
    }

    /**
     * Um unico metodo, e nao um par sobrecarregado: com {@code recover} nomeado, o Spring Retry nao
     * escolhe a sobrecarga mais especifica e devolveria o conflito de versao cru em vez do 409.
     */
    @Recover
    MatriculaAlocadaResponse recuperarNoMunicipio(RuntimeException causa, Long municipioId, Long turmaId) {
        if (causa instanceof OptimisticLockingFailureException) {
            log.warn(
                    "Alocacao da turma {} do municipio {} abandonada apos {} conflitos de versao",
                    turmaId,
                    municipioId,
                    MAXIMO_TENTATIVAS);
            throw new ConflitoException("Turma alterada por outra alocacao simultanea, tente novamente", causa);
        }
        throw causa;
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
