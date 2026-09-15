package org.alocacreche.relatorios.ocupacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.redis.testcontainers.RedisContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Cobre PAINEL-01, PAINEL-03, PAINEL-04, PAINEL-06 (agregação, município vazio, cache hit real,
 * TenantContext). PAINEL-05 (fail-open com Redis fora do ar) está em PainelPublicoFailOpenIT - uma
 * classe separada, porque aquele teste precisa DERRUBAR o Redis, o que contaminaria os testes desta
 * classe se compartilhassem o mesmo container.
 */
@SpringBootTest
@Testcontainers
class PainelPublicoServiceIT {

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
    @MockitoSpyBean private OcupacaoTurmaRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, 1L);
            entityManager.createNativeQuery("DELETE FROM ocupacao_turma").executeUpdate();
        });
    }

    @Test
    void obterPainelAgregaCorretamenteVagasCrechesETurmas() {
        seedTurma(501L, 5001L, 60L, 3, 1L);
        seedTurma(501L, 5002L, 61L, 0, 1L);
        seedTurma(501L, 5003L, 62L, 7, 1L);

        PainelPublicoDTO painel = service.obterPainel(501L);

        assertThat(painel.municipioId()).isEqualTo(501L);
        assertThat(painel.totalVagasDisponiveis()).isEqualTo(10L);
        assertThat(painel.totalCrechesComVaga()).isEqualTo(2L);
        assertThat(painel.totalTurmas()).isEqualTo(3L);
        assertThat(painel.atualizadoEm()).isNotNull();
    }

    @Test
    void obterPainelDeMunicipioSemDadosRetornaTotaisZerados() {
        PainelPublicoDTO painel = service.obterPainel(502L);

        assertThat(painel.totalVagasDisponiveis()).isZero();
        assertThat(painel.totalCrechesComVaga()).isZero();
        assertThat(painel.totalTurmas()).isZero();
        assertThat(painel.atualizadoEm()).isNull();
    }

    @Test
    void obterPainelSegundaChamadaVemDoCacheSemChamarRepositorioDeNovo() {
        seedTurma(503L, 5031L, 62L, 4, 1L);

        PainelPublicoDTO primeira = service.obterPainel(503L);
        PainelPublicoDTO segunda = service.obterPainel(503L);

        assertThat(segunda).isEqualTo(primeira);
        // Sinal decisivo: 1 chamada ao repositorio, nao 2 - prova que a 2a resposta veio do Redis
        // (deserializada corretamente, ja que "segunda" bate campo a campo com "primeira"), nao
        // recalculada do banco.
        verify(repository, times(1)).buscarPorFiltro(any(), any(), any());
    }

    private void seedTurma(
            Long municipioId, Long turmaId, Long crecheId, Integer vagas, Long eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            repository.upsert(turmaId, municipioId, crecheId, vagas, eventId);
        });
    }
}
