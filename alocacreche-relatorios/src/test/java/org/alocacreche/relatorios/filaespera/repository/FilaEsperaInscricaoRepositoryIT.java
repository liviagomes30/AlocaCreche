package org.alocacreche.relatorios.filaespera.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.alocacreche.relatorios.filaespera.domain.FilaEsperaInscricao;
import org.alocacreche.relatorios.filaespera.projection.RelatorioFilaEsperaProjection;
import org.alocacreche.relatorios.tenant.TenantContext;
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
 * Cobre FILA-01, FILA-02, FILA-03 (upsert idempotente, ordem/duplicata incluindo a fronteira de
 * igualdade, concorrencia), FILA-06 (RLS isola leitura por municipio) e FILA-10/14/15 (agregacao por
 * turma). Usa Postgres real (Testcontainers) porque o comportamento testado - INSERT ... ON CONFLICT
 * nativo + Row-Level Security - nao existe num banco em memoria.
 *
 * <p>Conecta como a role nao-superuser {@code alocacreche_app} (criada por
 * {@code db/relatorios-app-role.sql}), nunca como o usuario bootstrap do container - superusers
 * ignoram RLS incondicionalmente, mesmo com FORCE ROW LEVEL SECURITY (ver .specs/STATE.md AD-002).
 */
@SpringBootTest
@Testcontainers
class FilaEsperaInscricaoRepositoryIT {

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
    private FilaEsperaInscricaoRepository repository;

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
            TenantContext.aplicar(entityManager, 1L);
            entityManager.createNativeQuery("DELETE FROM fila_espera_inscricao").executeUpdate();
        });
    }

    @Test
    void upsertCriaLinhaQuandoInscricaoNaoExiste() {
        upsertComoTenant(1L, 1000L, 100L, 10L, LocalDate.now().minusDays(5), null, 1L);

        FilaEsperaInscricao inscricao = buscarComoTenant(1L, 1000L);
        assertThat(inscricao).isNotNull();
        assertThat(inscricao.getTurmaId()).isEqualTo(100L);
        assertThat(inscricao.getCrecheId()).isEqualTo(10L);
        assertThat(inscricao.getMunicipioId()).isEqualTo(1L);
        assertThat(inscricao.getDataSaida()).isNull();
        assertThat(inscricao.getUltimaSequenciaAplicada()).isEqualTo(1L);
    }

    @Test
    void upsertAtualizaQuandoSequenciaRecebidaEMaior() {
        LocalDate entrada = LocalDate.now().minusDays(10);
        upsertComoTenant(1L, 1001L, 100L, 10L, entrada, null, 1L);
        upsertComoTenant(1L, 1001L, 100L, 10L, entrada, LocalDate.now(), 2L);

        FilaEsperaInscricao inscricao = buscarComoTenant(1L, 1001L);
        assertThat(inscricao.getDataSaida()).isEqualTo(LocalDate.now());
        assertThat(inscricao.getUltimaSequenciaAplicada()).isEqualTo(2L);
    }

    @Test
    void upsertNaoAplicaQuandoSequenciaRecebidaEMenor() {
        LocalDate entrada = LocalDate.now().minusDays(10);
        upsertComoTenant(1L, 1002L, 100L, 10L, entrada, null, 5L);
        upsertComoTenant(1L, 1002L, 100L, 10L, entrada, LocalDate.now(), 3L);

        FilaEsperaInscricao inscricao = buscarComoTenant(1L, 1002L);
        assertThat(inscricao.getDataSaida()).isNull();
        assertThat(inscricao.getUltimaSequenciaAplicada()).isEqualTo(5L);
    }

    @Test
    void upsertNaoAplicaQuandoSequenciaRecebidaEIgual() {
        LocalDate entrada = LocalDate.now().minusDays(10);
        upsertComoTenant(1L, 1003L, 100L, 10L, entrada, null, 5L);
        upsertComoTenant(1L, 1003L, 100L, 10L, entrada, LocalDate.now(), 5L);

        FilaEsperaInscricao inscricao = buscarComoTenant(1L, 1003L);
        assertThat(inscricao.getDataSaida()).isNull();
        assertThat(inscricao.getUltimaSequenciaAplicada()).isEqualTo(5L);
    }

    @Test
    void upsertConcorrenteAplicaSoAMaiorSequencia() throws InterruptedException {
        LocalDate entrada = LocalDate.now().minusDays(10);
        upsertComoTenant(1L, 1004L, 100L, 10L, entrada, null, 1L);

        // CyclicBarrier forca as duas threads a chegarem no upsert praticamente no mesmo instante -
        // mesma tecnica de OcupacaoTurmaRepositoryIT para exercitar overlap real, nao so a garantia
        // teorica da clausula WHERE atomica.
        CyclicBarrier barreira = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        executor.submit(() -> {
            aguardarBarreira(barreira);
            upsertComoTenant(1L, 1004L, 100L, 10L, entrada, null, 5L);
            latch.countDown();
        });
        executor.submit(() -> {
            aguardarBarreira(barreira);
            upsertComoTenant(1L, 1004L, 100L, 10L, entrada, LocalDate.now(), 10L);
            latch.countDown();
        });
        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        FilaEsperaInscricao inscricao = buscarComoTenant(1L, 1004L);
        assertThat(inscricao.getUltimaSequenciaAplicada()).isEqualTo(10L);
        assertThat(inscricao.getDataSaida()).isEqualTo(LocalDate.now());
    }

    @Test
    void semSetLocalDefinidoFalhaFechadoEmVezDeVazarDados() {
        upsertComoTenant(1L, 1005L, 100L, 10L, LocalDate.now().minusDays(1), null, 1L);

        // Fail-closed: sem SET LOCAL, a query sempre lanca excecao - mesma prova de
        // OcupacaoTurmaRepositoryIT. A mensagem exata do Postgres varia com o estado da conexao do
        // pool, por isso a asserçao e sobre o tipo (falha), nao a string exata.
        assertThatThrownBy(
                        () ->
                                transactionTemplate.executeWithoutResult(
                                        status ->
                                                entityManager
                                                        .createQuery(
                                                                "SELECT f FROM FilaEsperaInscricao f WHERE f.inscricaoId = :id")
                                                        .setParameter("id", 1005L)
                                                        .getResultList()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rlsEscondeLinhaDeOutroMunicipio() {
        upsertComoTenant(1L, 1006L, 100L, 10L, LocalDate.now().minusDays(1), null, 1L);

        List<?> resultadoComoTenantErrado = transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, 2L);
            return entityManager
                    .createQuery("SELECT f FROM FilaEsperaInscricao f WHERE f.inscricaoId = :id")
                    .setParameter("id", 1006L)
                    .getResultList();
        });

        assertThat(resultadoComoTenantErrado).isEmpty();
    }

    @Test
    void buscarRelatorioComUmaInscricaoAguardandoRetornaContagemUmETempoMedioMaiorQueZero() {
        upsertComoTenant(1L, 2000L, 200L, 20L, LocalDate.now().minusDays(3), null, 1L);

        List<RelatorioFilaEsperaProjection> resultado = buscarRelatorioComoTenant(1L, 1L, null, null);

        assertThat(resultado).hasSize(1);
        RelatorioFilaEsperaProjection linha = resultado.get(0);
        assertThat(linha.getTurmaId()).isEqualTo(200L);
        assertThat(linha.getQuantidadeAguardando()).isEqualTo(1L);
        assertThat(linha.getTempoMedioEsperaDias()).isGreaterThan(0.0);
    }

    @Test
    void buscarRelatorioTurmaSemNenhumaLinhaNaoApareceNoResultado() {
        upsertComoTenant(1L, 2001L, 201L, 20L, LocalDate.now().minusDays(3), null, 1L);

        List<RelatorioFilaEsperaProjection> resultado = buscarRelatorioComoTenant(1L, 1L, null, 999999L);

        assertThat(resultado).isEmpty();
    }

    @Test
    void buscarRelatorioSoComInscricoesJaSaidasRetornaContagemZero() {
        upsertComoTenant(1L, 2002L, 202L, 20L, LocalDate.now().minusDays(10), LocalDate.now().minusDays(2), 1L);

        List<RelatorioFilaEsperaProjection> resultado = buscarRelatorioComoTenant(1L, 1L, null, 202L);

        assertThat(resultado).hasSize(1);
        RelatorioFilaEsperaProjection linha = resultado.get(0);
        assertThat(linha.getQuantidadeAguardando()).isEqualTo(0L);
        assertThat(linha.getTempoMedioEsperaDias()).isEqualTo(8.0);
    }

    @Test
    void buscarRelatorioFiltraPorCrecheId() {
        upsertComoTenant(1L, 2003L, 210L, 21L, LocalDate.now().minusDays(1), null, 1L);
        upsertComoTenant(1L, 2004L, 211L, 22L, LocalDate.now().minusDays(1), null, 1L);

        List<RelatorioFilaEsperaProjection> resultado = buscarRelatorioComoTenant(1L, 1L, 21L, null);

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).getTurmaId()).isEqualTo(210L);
    }

    @Test
    void buscarRelatorioFiltraPorTurmaId() {
        upsertComoTenant(1L, 2005L, 220L, 23L, LocalDate.now().minusDays(1), null, 1L);
        upsertComoTenant(1L, 2006L, 221L, 23L, LocalDate.now().minusDays(1), null, 1L);

        List<RelatorioFilaEsperaProjection> resultado = buscarRelatorioComoTenant(1L, 1L, null, 220L);

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).getTurmaId()).isEqualTo(220L);
    }

    @Test
    void buscarRelatorioNaoRetornaLinhaDeOutroTenantMesmoComMunicipioIdCorretoNoFiltro() {
        upsertComoTenant(1L, 2007L, 230L, 24L, LocalDate.now().minusDays(1), null, 1L);

        // Sessao com tenant 2 (errado), mas o filtro pede municipioId=1 (o correto) - a RLS deve
        // barrar mesmo assim, mesma prova de OcupacaoTurmaRepositoryIT.
        List<RelatorioFilaEsperaProjection> resultado = buscarRelatorioComoTenant(2L, 1L, null, null);

        assertThat(resultado).isEmpty();
    }

    private void upsertComoTenant(
            Long municipioId,
            Long inscricaoId,
            Long turmaId,
            Long crecheId,
            LocalDate dataEntrada,
            LocalDate dataSaida,
            Long eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            repository.upsert(inscricaoId, turmaId, crecheId, municipioId, dataEntrada, dataSaida, eventId);
        });
    }

    private FilaEsperaInscricao buscarComoTenant(Long municipioId, Long inscricaoId) {
        return transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            return entityManager.find(FilaEsperaInscricao.class, inscricaoId);
        });
    }

    private List<RelatorioFilaEsperaProjection> buscarRelatorioComoTenant(
            Long tenantDaSessao, Long municipioIdFiltro, Long crecheIdFiltro, Long turmaIdFiltro) {
        return transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, tenantDaSessao);
            return repository.buscarRelatorio(municipioIdFiltro, crecheIdFiltro, turmaIdFiltro);
        });
    }

    private void aguardarBarreira(CyclicBarrier barreira) {
        try {
            barreira.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
