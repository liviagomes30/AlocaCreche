package org.alocacreche.relatorios.ocupacao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.redis.testcontainers.RedisContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.alocacreche.relatorios.filaespera.repository.FilaEsperaInscricaoRepository;
import org.alocacreche.relatorios.ocupacao.dto.PainelPublicoDTO;
import org.alocacreche.relatorios.ocupacao.event.VagaLiberadaEvent;
import org.alocacreche.relatorios.ocupacao.listener.VagaLiberadaListener;
import org.alocacreche.relatorios.ocupacao.repository.OcupacaoTurmaRepository;
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
 * TenantContext) e a invalidação do cache quando chega um evento de vaga liberada. PAINEL-05 (fail-open com Redis fora do ar) está em PainelPublicoFailOpenIT - uma
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
    @Autowired private VagaLiberadaListener vagaLiberadaListener;
    @MockitoSpyBean private OcupacaoTurmaRepository repository;
    @Autowired private FilaEsperaInscricaoRepository filaRepository;
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
            entityManager.createNativeQuery("DELETE FROM fila_espera_inscricao").executeUpdate();
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

    @Test
    void eventoDeVagaLiberadaInvalidaOCacheEOPainelRefleteOValorNovo() {
        seedTurma(504L, 5041L, 63L, 3, 1L);
        PainelPublicoDTO antes = service.obterPainel(504L);
        assertThat(antes.totalVagasDisponiveis()).isEqualTo(3L);

        vagaLiberadaListener.receber(
                new VagaLiberadaEvent(2L, "VagaLiberada", 504L, 5041L, 63L, 5, LocalDateTime.now()));

        // Sem a invalidacao, o painel devolveria 3 por ate 60s (TTL do cache).
        assertThat(service.obterPainel(504L).totalVagasDisponiveis()).isEqualTo(5L);
    }

    @Test
    void obterPainelSomaVagasPorFaixaNaOrdemDoBercarioAPreEscola() {
        seedTurmaDetalhada(510L, 5101L, 70L, 2, "PRE_ESCOLA", "Creche Lua", "Centro", 1L);
        seedTurmaDetalhada(510L, 5102L, 71L, 3, "BERCARIO", "Creche Sol", "Norte", 2L);
        seedTurmaDetalhada(510L, 5103L, 72L, 4, "BERCARIO", "Creche Mar", "Sul", 3L);

        PainelPublicoDTO painel = service.obterPainel(510L);

        assertThat(painel.vagasPorFaixa())
                .containsExactly(
                        new PainelPublicoDTO.VagasPorFaixa("BERCARIO", 7L, 2L),
                        new PainelPublicoDTO.VagasPorFaixa("PRE_ESCOLA", 2L, 1L));
    }

    @Test
    void obterPainelListaCrechesComVagaOrdenadasEIgnoraTurmasLotadas() {
        seedTurmaDetalhada(511L, 5111L, 80L, 1, "MATERNAL", "Creche Beta", "Centro", 1L);
        seedTurmaDetalhada(511L, 5112L, 81L, 6, "BERCARIO", "Creche Alfa", "Norte", 2L);
        seedTurmaDetalhada(511L, 5113L, 82L, 0, "MATERNAL", "Creche Lotada", "Sul", 3L);
        seedTurma(511L, 5114L, 83L, 1, 4L);

        PainelPublicoDTO painel = service.obterPainel(511L);

        assertThat(painel.crechesComVaga())
                .extracting(PainelPublicoDTO.CrecheComVaga::nome)
                .containsExactly("Creche Alfa", "Creche #83", "Creche Beta");
        assertThat(painel.crechesComVaga().get(0).bairro()).isEqualTo("Norte");
        assertThat(painel.crechesComVaga().get(0).faixaEtaria()).isEqualTo("BERCARIO");
    }

    @Test
    void obterPainelOcultaFilaAbaixoDoMinimoParaNaoIdentificarFamilias() {
        seedFila(512L, 3);

        PainelPublicoDTO painel = service.obterPainel(512L);

        assertThat(painel.familiasAguardando()).isNull();
        assertThat(painel.familiasAguardandoAbaixoDoMinimo()).isTrue();
        assertThat(painel.esperaMediaDias()).isNull();
    }

    @Test
    void obterPainelDivulgaFilaAgregadaQuandoHaFamiliasSuficientes() {
        seedFila(513L, 6);

        PainelPublicoDTO painel = service.obterPainel(513L);

        assertThat(painel.familiasAguardando()).isEqualTo(6L);
        assertThat(painel.familiasAguardandoAbaixoDoMinimo()).isFalse();
        assertThat(painel.esperaMediaDias()).isNotNull().isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void obterPainelSemNinguemNaFilaNaoMarcaAbaixoDoMinimo() {
        PainelPublicoDTO painel = service.obterPainel(514L);

        assertThat(painel.familiasAguardando()).isZero();
        assertThat(painel.familiasAguardandoAbaixoDoMinimo()).isFalse();
    }

    private void seedTurmaDetalhada(
            Long municipioId, Long turmaId, Long crecheId, Integer vagas,
            String faixa, String nome, String bairro, Long eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            repository.upsert(turmaId, municipioId, crecheId, vagas, faixa, nome, bairro, eventId);
        });
    }

    private void seedFila(Long municipioId, int familias) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            for (int i = 1; i <= familias; i++) {
                filaRepository.upsert(
                        municipioId * 100 + i, 9000L, 90L, municipioId,
                        LocalDate.now().minusDays(i), null, (long) i);
            }
        });
    }

    private void seedTurma(
            Long municipioId, Long turmaId, Long crecheId, Integer vagas, Long eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            repository.upsert(turmaId, municipioId, crecheId, vagas, eventId);
        });
    }
}
