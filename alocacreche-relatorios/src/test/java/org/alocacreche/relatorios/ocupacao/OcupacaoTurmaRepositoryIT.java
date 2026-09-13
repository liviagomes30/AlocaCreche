package org.alocacreche.relatorios.ocupacao;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Cobre OCUP-01, OCUP-02, OCUP-03 (upsert idempotente, ordem/duplicata, concorrencia) e OCUP-10
 * (RLS isola leitura por municipio). Usa Postgres real (Testcontainers) porque o comportamento
 * testado - INSERT ... ON CONFLICT nativo + Row-Level Security - nao existe num banco em memoria.
 *
 * <p>Conecta como a role nao-superuser {@code alocacreche_app} (criada por
 * {@code db/relatorios-app-role.sql}), nunca como o usuario bootstrap do container - superusers
 * ignoram RLS incondicionalmente, mesmo com FORCE ROW LEVEL SECURITY (ver .specs/STATE.md AD-002).
 */
@SpringBootTest
@Testcontainers
class OcupacaoTurmaRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired
    private OcupacaoTurmaRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL app.current_tenant = '1'").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM ocupacao_turma").executeUpdate();
        });
    }

    @Test
    void upsertCriaLinhaQuandoTurmaNaoExiste() {
        upsertComoTenant(1L, 100L, 10L, 5, 1L);

        OcupacaoTurma turma = buscarComoTenant(1L, 100L);
        assertThat(turma).isNotNull();
        assertThat(turma.getVagasDisponiveis()).isEqualTo(5);
        assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(1L);
        assertThat(turma.getCrecheId()).isEqualTo(10L);
        assertThat(turma.getMunicipioId()).isEqualTo(1L);
    }

    @Test
    void upsertAtualizaQuandoSequenciaRecebidaEMaior() {
        upsertComoTenant(1L, 100L, 10L, 5, 1L);
        upsertComoTenant(1L, 100L, 10L, 3, 2L);

        OcupacaoTurma turma = buscarComoTenant(1L, 100L);
        assertThat(turma.getVagasDisponiveis()).isEqualTo(3);
        assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(2L);
    }

    @Test
    void upsertNaoAplicaQuandoSequenciaRecebidaEMenorOuIgual() {
        upsertComoTenant(1L, 100L, 10L, 5, 5L);
        upsertComoTenant(1L, 100L, 10L, 999, 3L);

        OcupacaoTurma turma = buscarComoTenant(1L, 100L);
        assertThat(turma.getVagasDisponiveis()).isEqualTo(5);
        assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(5L);
    }

    @Test
    void upsertConcorrenteAplicaSoAMaiorSequencia() throws InterruptedException {
        upsertComoTenant(1L, 100L, 10L, 10, 1L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        executor.submit(() -> {
            upsertComoTenant(1L, 100L, 10L, 3, 5L);
            latch.countDown();
        });
        executor.submit(() -> {
            upsertComoTenant(1L, 100L, 10L, 7, 10L);
            latch.countDown();
        });
        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        OcupacaoTurma turma = buscarComoTenant(1L, 100L);
        assertThat(turma.getUltimaSequenciaAplicada()).isEqualTo(10L);
        assertThat(turma.getVagasDisponiveis()).isEqualTo(7);
    }

    @Test
    void rlsEscondeLinhaDeOutroMunicipio() {
        upsertComoTenant(1L, 200L, 20L, 8, 1L);

        List<?> resultadoComoTenantErrado = transactionTemplate.execute(status -> {
            entityManager.createNativeQuery("SET LOCAL app.current_tenant = '2'").executeUpdate();
            return entityManager
                    .createQuery("SELECT o FROM OcupacaoTurma o WHERE o.turmaId = :id")
                    .setParameter("id", 200L)
                    .getResultList();
        });

        assertThat(resultadoComoTenantErrado).isEmpty();
    }

    private void upsertComoTenant(
            Long municipioId, Long turmaId, Long crecheId, Integer vagas, Long eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager
                    .createNativeQuery("SET LOCAL app.current_tenant = '" + municipioId + "'")
                    .executeUpdate();
            repository.upsert(turmaId, municipioId, crecheId, vagas, eventId);
        });
    }

    private OcupacaoTurma buscarComoTenant(Long municipioId, Long turmaId) {
        return transactionTemplate.execute(status -> {
            entityManager
                    .createNativeQuery("SET LOCAL app.current_tenant = '" + municipioId + "'")
                    .executeUpdate();
            return entityManager.find(OcupacaoTurma.class, turmaId);
        });
    }
}
