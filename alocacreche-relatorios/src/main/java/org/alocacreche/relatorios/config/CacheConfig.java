package org.alocacreche.relatorios.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Override
    public CacheErrorHandler errorHandler() {
        return new FailOpenCacheErrorHandler();
    }

    private class FailOpenCacheErrorHandler implements CacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            log.warn(
                    "Falha ao ler do cache '{}' (chave={}) - prosseguindo sem cache",
                    cache.getName(),
                    key,
                    exception);
        }

        @Override
        public void handleCachePutError(
                RuntimeException exception, Cache cache, Object key, Object value) {
            log.warn(
                    "Falha ao escrever no cache '{}' (chave={}) - valor nao cacheado",
                    cache.getName(),
                    key,
                    exception);
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            log.warn("Falha ao invalidar o cache '{}' (chave={})", cache.getName(), key, exception);
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            log.warn("Falha ao limpar o cache '{}'", cache.getName(), exception);
        }
    }
}
