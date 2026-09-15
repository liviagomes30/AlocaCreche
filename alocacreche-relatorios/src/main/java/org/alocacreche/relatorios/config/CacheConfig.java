package org.alocacreche.relatorios.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita a abstracao de cache do Spring (usada por PainelPublicoService). O CacheErrorHandler
 * customizado implementa fail-open: uma falha de conexao com o Redis nunca deve quebrar um endpoint
 * cujo cache e so uma otimizacao, nao um requisito de corretude (ver design.md,
 * relatorios-painel-publico). Sem esse handler, o comportamento padrao do Spring
 * (SimpleCacheErrorHandler) relancaria a excecao e derrubaria a requisicao.
 *
 * <p>NAO usar sync=true em nenhum @Cacheable que dependa deste fail-open - falhas de conexao em modo
 * sincrono pulam o CacheErrorHandler.
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Falha ao ler do cache '{}' (chave={}) - prosseguindo sem cache", cache.getName(), key, exception);
            }

            @Override
            public void handleCachePutError(
                    RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Falha ao escrever no cache '{}' (chave={}) - valor nao cacheado", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Falha ao invalidar o cache '{}' (chave={})", cache.getName(), key, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Falha ao limpar o cache '{}'", cache.getName(), exception);
            }
        };
    }
}
