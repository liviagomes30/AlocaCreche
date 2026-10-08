package org.alocacreche.core.matricula.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reage a vaga liberada alocando o proximo da fila. Bean separado de {@link AlocacaoVagaService}
 * de proposito: a chamada atravessa o proxy, entao o {@code @Retryable} de la e o
 * {@code @Transactional(REQUIRES_NEW)} de {@link AlocacaoVagaTransacional} valem (self-invocation,
 * docs/arquitetura/notas-tecnicas.md).
 *
 * <p>Roda depois do commit da desistencia, com o municipio que veio no evento (nao depende do
 * token). Uma falha aqui nao desfaz a desistencia, ja confirmada: so e registrada. Se este evento
 * se perder (queda entre o commit e o listener) ou a alocacao falhar, a proxima rodada de
 * {@link ManutencaoMatriculaScheduler} encontra a turma com vaga livre e fila nao vazia.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlocacaoAutomaticaListener {

    private final AlocacaoVagaService alocacaoVagaService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void aoLiberarVaga(VagaLiberadaInternalEvent evento) {
        try {
            MatriculaAlocadaResponse alocada = alocacaoVagaService.alocarProximoElegivelNoMunicipio(evento.municipioId(), evento.turmaId());
            log.info(
                    "Vaga liberada na turma {} do municipio {} realocada na matricula {}",
                    evento.turmaId(),
                    evento.municipioId(),
                    alocada.matriculaId());
        } catch (ConflitoException excecao) {
            log.info(
                    "Vaga liberada na turma {} do municipio {} nao realocada: {}",
                    evento.turmaId(),
                    evento.municipioId(),
                    excecao.getMessage());
        } catch (RuntimeException excecao) {
            log.error(
                    "Falha ao realocar a vaga liberada na turma {} do municipio {}; exige alocacao manual",
                    evento.turmaId(),
                    evento.municipioId(),
                    excecao);
        }
    }
}
