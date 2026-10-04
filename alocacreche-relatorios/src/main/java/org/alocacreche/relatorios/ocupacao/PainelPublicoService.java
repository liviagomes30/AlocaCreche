package org.alocacreche.relatorios.ocupacao;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PainelPublicoService {

    public static final String CACHE_PAINEL_PUBLICO = "painel-publico";

    private final OcupacaoTurmaRepository repository;
    private final EntityManager entityManager;

    @Cacheable(cacheNames = CACHE_PAINEL_PUBLICO, key = "#municipioId")
    @Transactional(readOnly = true)
    public PainelPublicoDTO obterPainel(Long municipioId) {
        TenantContext.aplicar(entityManager, municipioId);

        List<OcupacaoTurma> turmas = repository.buscarPorFiltro(municipioId, null, null);

        long totalVagasDisponiveis = 0;
        Set<Long> crechesComVaga = new HashSet<>();
        LocalDateTime atualizadoEm = null;
        for (OcupacaoTurma turma : turmas) {
            totalVagasDisponiveis += turma.getVagasDisponiveis();
            if (turma.getVagasDisponiveis() > 0) {
                crechesComVaga.add(turma.getCrecheId());
            }
            if (atualizadoEm == null || turma.getAtualizadoEm().isAfter(atualizadoEm)) {
                atualizadoEm = turma.getAtualizadoEm();
            }
        }

        return new PainelPublicoDTO(
                municipioId, totalVagasDisponiveis, (long) crechesComVaga.size(), (long) turmas.size(), atualizadoEm);
    }
}
