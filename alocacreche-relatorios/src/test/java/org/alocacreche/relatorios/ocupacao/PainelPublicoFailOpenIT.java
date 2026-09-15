package org.alocacreche.relatorios.ocupacao;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Cobre PAINEL-05 (fail-open quando o Redis está indisponível) em classe própria - este teste
 * DERRUBA o container Redis deliberadamente, o que contaminaria PainelPublicoServiceIT se
 * compartilhassem o mesmo container (Redis não volta ao ar sozinho depois de parado).
 */
@SpringBootTest
@Testcontainers
class PainelPublicoFailOpenIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @Container
    static RedisContainer redis =
            new RedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private PainelPublicoService service;
    @Autowired private OcupacaoTurmaRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void obterPainelContinuaFuncionandoComRedisIndisponivel() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, 900L);
            repository.upsert(9001L, 900L, 90L, 5, 1L);
        });

        redis.stop();

        PainelPublicoDTO painel = service.obterPainel(900L);

        assertThat(painel.totalVagasDisponiveis()).isEqualTo(5L);
        assertThat(painel.totalCrechesComVaga()).isEqualTo(1L);
        assertThat(painel.totalTurmas()).isEqualTo(1L);
    }
}
