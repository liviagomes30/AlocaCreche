package org.alocacreche.relatorios.ocupacao.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Garante que o painel publico e invalidado SOMENTE depois do commit da transacao que gravou a
 * projecao (invalidar antes deixaria uma leitura concorrente repopular o cache com o valor antigo)
 * e que uma falha do Redis nunca derruba o consumo do evento (fail-open, igual ao CacheConfig).
 */
@ExtendWith(MockitoExtension.class)
class PainelPublicoCacheInvalidadorTest {

    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    private PainelPublicoCacheInvalidador invalidador;

    @BeforeEach
    void setUp() {
        invalidador = new PainelPublicoCacheInvalidador(cacheManager);
    }

    @AfterEach
    void limparSincronizacao() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void comTransacaoAtivaSoInvalidaDepoisDoCommit() {
        when(cacheManager.getCache(PainelPublicoService.CACHE_PAINEL_PUBLICO)).thenReturn(cache);
        TransactionSynchronizationManager.initSynchronization();

        invalidador.invalidarAposCommit(7L);

        verify(cache, never()).evict(7L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(cache).evict(7L);
    }

    @Test
    void transacaoComRollbackNaoInvalidaOCache() {
        TransactionSynchronizationManager.initSynchronization();

        invalidador.invalidarAposCommit(7L);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(cacheManager, never()).getCache(PainelPublicoService.CACHE_PAINEL_PUBLICO);
    }

    @Test
    void semTransacaoAtivaInvalidaImediatamente() {
        when(cacheManager.getCache(PainelPublicoService.CACHE_PAINEL_PUBLICO)).thenReturn(cache);

        invalidador.invalidarAposCommit(7L);

        verify(cache).evict(7L);
    }

    @Test
    void falhaDoCacheNaoPropagaExcecao() {
        when(cacheManager.getCache(PainelPublicoService.CACHE_PAINEL_PUBLICO)).thenReturn(cache);
        doThrow(new IllegalStateException("redis fora do ar")).when(cache).evict(7L);

        invalidador.invalidarAposCommit(7L);

        verify(cache).evict(7L);
    }
}
