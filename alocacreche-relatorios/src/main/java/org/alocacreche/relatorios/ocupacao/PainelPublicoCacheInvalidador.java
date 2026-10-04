package org.alocacreche.relatorios.ocupacao;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class PainelPublicoCacheInvalidador {

    private final CacheManager cacheManager;

    public void invalidarAposCommit(Long municipioId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidar(municipioId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        invalidar(municipioId);
                    }
                });
    }

    private void invalidar(Long municipioId) {
        try {
            Cache cache = cacheManager.getCache(PainelPublicoService.CACHE_PAINEL_PUBLICO);
            if (cache != null) {
                cache.evict(municipioId);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Falha ao invalidar o painel publico (municipioId={}) - painel defasado ate o TTL",
                    municipioId,
                    e);
        }
    }
}
