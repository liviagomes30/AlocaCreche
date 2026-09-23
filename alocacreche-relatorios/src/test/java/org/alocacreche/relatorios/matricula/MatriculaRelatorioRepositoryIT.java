package org.alocacreche.relatorios.matricula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
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
 * Cobre upsert idempotente (criacao, ordem/duplicata), RLS por municipio e as duas queries de
 * relatorio (distancia media, taxa de confirmacao) - mesmo padrao de FilaEsperaInscricaoRepositoryIT,
 * com o conjunto de cenarios reduzido de proposito (ver .specs/STATE.md, handoff
 * relatorios-matricula): a mecanica de concorrencia/fail-closed do upsert nativo ja foi provada la e
 * em OcupacaoTurmaRepositoryIT, nao repetida aqui.
 */
@SpringBootTest
@Testcontainers
class MatriculaRelatorioRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private MatriculaRelatorioRepository repository;

    @Autowired private PlatformTransactionManager transactionManager;

    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(
                status -> {
                    TenantContext.aplicar(entityManager, 1L);
                    entityManager.createNativeQuery("DELETE FROM matricula_relatorio").executeUpdate();
                });
    }

    @Test
    void upsertCriaLinhaQuandoMatriculaNaoExiste() {
        upsertComoTenant(1L, 900L, 500L, 100L, 10L, StatusMatricula.PENDENTE, 1.5, LocalDate.now(), null, 1L);

        MatriculaRelatorio matricula = buscarComoTenant(1L, 900L);
        assertThat(matricula).isNotNull();
        assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.PENDENTE);
        assertThat(matricula.getDistanciaKm()).isEqualTo(1.5);
        assertThat(matricula.getUltimaSequenciaAplicada()).isEqualTo(1L);
    }

    @Test
    void upsertAtualizaQuandoSequenciaRecebidaEMaior() {
        LocalDate convocacao = LocalDate.now().minusDays(5);
        upsertComoTenant(1L, 901L, 500L, 100L, 10L, StatusMatricula.PENDENTE, 1.5, convocacao, null, 1L);
        upsertComoTenant(
                1L, 901L, 500L, 100L, 10L, StatusMatricula.CONFIRMADA, 1.5, convocacao, LocalDate.now(), 2L);

        MatriculaRelatorio matricula = buscarComoTenant(1L, 901L);
        assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.CONFIRMADA);
        assertThat(matricula.getUltimaSequenciaAplicada()).isEqualTo(2L);
    }

    @Test
    void upsertNaoAplicaQuandoSequenciaRecebidaEMenor() {
        LocalDate convocacao = LocalDate.now().minusDays(5);
        upsertComoTenant(1L, 902L, 500L, 100L, 10L, StatusMatricula.CONFIRMADA, 1.5, convocacao, LocalDate.now(), 5L);
        upsertComoTenant(1L, 902L, 500L, 100L, 10L, StatusMatricula.PENDENTE, 1.5, convocacao, null, 3L);

        MatriculaRelatorio matricula = buscarComoTenant(1L, 902L);
        assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.CONFIRMADA);
        assertThat(matricula.getUltimaSequenciaAplicada()).isEqualTo(5L);
    }

    @Test
    void rlsEscondeLinhaDeOutroMunicipio() {
        upsertComoTenant(1L, 903L, 500L, 100L, 10L, StatusMatricula.PENDENTE, 1.5, LocalDate.now(), null, 1L);

        MatriculaRelatorio comoTenantErrado = buscarComoTenant(2L, 903L);

        assertThat(comoTenantErrado).isNull();
    }

    @Test
    void calcularDistanciaMediaConsideraSoMatriculasDoMesDoPeriodo() {
        LocalDate dentroDoMes = LocalDate.of(2026, 3, 10);
        LocalDate foraDoMes = LocalDate.of(2026, 2, 20);
        upsertComoTenant(1L, 910L, 500L, 100L, 10L, StatusMatricula.PENDENTE, 2.0, dentroDoMes, null, 1L);
        upsertComoTenant(1L, 911L, 501L, 100L, 10L, StatusMatricula.PENDENTE, 4.0, dentroDoMes, null, 1L);
        upsertComoTenant(1L, 912L, 502L, 100L, 10L, StatusMatricula.PENDENTE, 100.0, foraDoMes, null, 1L);

        Double media = buscarDistanciaMediaComoTenant(1L, 1L, LocalDate.of(2026, 3, 1));

        assertThat(media).isCloseTo(3.0, within(0.001));
    }

    @Test
    void calcularDistanciaMediaSemMatriculaNoPeriodoRetornaNulo() {
        Double media = buscarDistanciaMediaComoTenant(1L, 1L, LocalDate.of(2026, 3, 1));

        assertThat(media).isNull();
    }

    @Test
    void calcularTaxaConfirmacaoContaTotalEConfirmadasDoMesDoPeriodo() {
        LocalDate periodo = LocalDate.of(2026, 4, 1);
        upsertComoTenant(
                1L, 920L, 500L, 100L, 10L, StatusMatricula.CONFIRMADA, 1.0, LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 8), 1L);
        upsertComoTenant(1L, 921L, 501L, 100L, 10L, StatusMatricula.PENDENTE, 1.0, LocalDate.of(2026, 4, 6), null, 1L);
        upsertComoTenant(1L, 922L, 502L, 100L, 10L, StatusMatricula.EXPIRADA, 1.0, LocalDate.of(2026, 4, 7), null, 1L);

        RelatorioConfirmacaoProjection resultado =
                transactionTemplate.execute(
                        status -> {
                            TenantContext.aplicar(entityManager, 1L);
                            return repository.calcularTaxaConfirmacao(1L, periodo);
                        });

        assertThat(resultado.getTotalMatriculas()).isEqualTo(3L);
        assertThat(resultado.getTotalConfirmadas()).isEqualTo(1L);
    }

    private void upsertComoTenant(
            Long municipioId,
            Long matriculaId,
            Long inscricaoId,
            Long turmaId,
            Long crecheId,
            StatusMatricula status,
            Double distanciaKm,
            LocalDate dataConvocacao,
            LocalDate dataConfirmacao,
            Long eventId) {
        transactionTemplate.executeWithoutResult(
                status2 -> {
                    TenantContext.aplicar(entityManager, municipioId);
                    repository.upsert(
                            matriculaId,
                            inscricaoId,
                            turmaId,
                            crecheId,
                            municipioId,
                            status.name(),
                            distanciaKm,
                            dataConvocacao,
                            dataConfirmacao,
                            eventId);
                });
    }

    private MatriculaRelatorio buscarComoTenant(Long municipioId, Long matriculaId) {
        return transactionTemplate.execute(
                status -> {
                    TenantContext.aplicar(entityManager, municipioId);
                    return entityManager.find(MatriculaRelatorio.class, matriculaId);
                });
    }

    private Double buscarDistanciaMediaComoTenant(Long tenantDaSessao, Long municipioIdFiltro, LocalDate periodo) {
        return transactionTemplate.execute(
                status -> {
                    TenantContext.aplicar(entityManager, tenantDaSessao);
                    return repository.calcularDistanciaMedia(municipioIdFiltro, periodo);
                });
    }
}
