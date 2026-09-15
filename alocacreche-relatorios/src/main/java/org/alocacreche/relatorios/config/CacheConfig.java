package org.alocacreche.relatorios.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita a abstracao de cache do Spring (usada por PainelPublicoService). O CacheErrorHandler
 * customizado implementa fail-open: uma falha de conexao com o Redis nunca deve quebrar um endpoint
 * cujo cache e so uma otimizacao, nao um requisito de corretude (ver design.md,
 * relatorios-painel-publico). Sem esse handler, o comportamento padrao do Spring
 * (SimpleCacheErrorHandler) relanca a excecao e derruba a requisicao.
 *
 * <p>Implementa CachingConfigurer em vez de so declarar um @Bean CacheErrorHandler solto - um teste
 * real (PainelPublicoFailOpenIT, derrubando o container Redis) provou que um @Bean solto NUNCA e
 * conectado ao CacheInterceptor: ProxyCachingConfiguration (por baixo do @EnableCaching) so busca o
 * errorHandler atraves de um bean CachingConfigurer, nao por tipo direto - sem essa interface, o
 * interceptor sempre usa o SimpleCacheErrorHandler padrao (relanca), nunca o bean customizado.
 *
 * <p>NAO usar sync=true em nenhum @Cacheable que dependa deste fail-open - falhas de conexao em modo
 * sincrono pulam o CacheErrorHandler mesmo com CachingConfigurer.
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Override
    public CacheErrorHandler errorHandler() {
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
