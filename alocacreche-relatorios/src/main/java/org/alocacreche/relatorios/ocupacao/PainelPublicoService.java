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

/**
 * Painel público agregado (ver docs/arquitetura/visao-geral.md, consultarPainelPublico). Reusa
 * OcupacaoTurmaRepository.buscarPorFiltro (mesma consulta do relatório detalhado) e agrega em
 * memória - volume de dados por município é pequeno o suficiente para não precisar de uma query SQL
 * agregada dedicada (ver design.md, relatorios-painel-publico).
 */
@Service
@RequiredArgsConstructor
public class PainelPublicoService {

    private final OcupacaoTurmaRepository repository;
    private final EntityManager entityManager;

    /**
     * Sem sync=true de propósito - não é por causa do fail-open (sync=true não afeta o
     * CacheErrorHandler, ver CacheConfig), e sim para não serializar chamadas simultâneas em cache
     * miss (aceitando um thundering herd pequeno em vez de travar requisições concorrentes).
     */
    @Cacheable(cacheNames = "painel-publico", key = "#municipioId")
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
