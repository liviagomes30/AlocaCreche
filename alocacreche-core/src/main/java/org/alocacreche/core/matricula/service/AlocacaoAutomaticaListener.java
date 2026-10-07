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
 * <p>Roda depois do commit da desistencia, na mesma thread da requisicao - por isso o municipio
 * ainda vem do token. Uma falha aqui nao desfaz a desistencia, ja confirmada: so e registrada.
 *
 * <p>TODO: job {@code @Scheduled} de reconciliacao ({@code reconciliarVagasDisponiveis} junto de
 * {@code verificarPrazosVencidos}, a cada 2 minutos - ADR 0001, decisoes 3 e 19), iterando por
 * municipio com {@code SET LOCAL} num bean separado. Adiado pela ADR 0004: sem ele, se este
 * evento se perder (queda entre o commit e o listener) ou a alocacao falhar, a vaga fica livre
 * ate alguem chamar {@code POST /api/v1/matricula/vagas/alocar} manualmente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlocacaoAutomaticaListener {

    private final AlocacaoVagaService alocacaoVagaService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void aoLiberarVaga(VagaLiberadaInternalEvent evento) {
        try {
            MatriculaAlocadaResponse alocada = alocacaoVagaService.alocarProximoElegivel(evento.turmaId());
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
