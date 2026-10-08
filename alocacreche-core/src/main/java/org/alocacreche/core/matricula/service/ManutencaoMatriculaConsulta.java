package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.alocacreche.core.matricula.TurmaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Varreduras do job, cada uma numa transacao so de leitura com o {@code SET LOCAL} do municipio -
 * o job nao tem token, e a RLS recusaria a consulta sem tenant (nunca um role com BYPASSRLS).
 */
@Component
@RequiredArgsConstructor
public class ManutencaoMatriculaConsulta {

    private final EntityManager entityManager;
    private final MatriculaRepository matriculaRepository;
    private final TurmaRepository turmaRepository;

    @Transactional(readOnly = true)
    public List<Long> matriculasComPrazoVencido(Long municipioId) {
        TenantContext.aplicar(entityManager, municipioId);
        return matriculaRepository.buscarIdsComPrazoVencido(StatusMatricula.PENDENTE, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<Long> turmasComVagaEFila(Long municipioId) {
        TenantContext.aplicar(entityManager, municipioId);
        return turmaRepository.buscarIdsComVagaEFila();
    }
}
