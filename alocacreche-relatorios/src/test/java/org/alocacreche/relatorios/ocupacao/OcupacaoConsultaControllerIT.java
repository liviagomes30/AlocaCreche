package org.alocacreche.relatorios.ocupacao;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cobre RELOC-01 a RELOC-06 via HTTP real (Testcontainers Postgres). RELOC-07 (autenticação) é
 * intencionalmente não coberto - ver spec.md, Out of Scope (TODO documentado).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OcupacaoConsultaControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private OcupacaoTurmaRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        // TRUNCATE via a conexao padrao do container (superuser, ignora RLS) - os testes desta
        // classe escrevem sob mais de um municipioId, entao um DELETE escopado a um unico tenant
        // (como o padrao usado em OcupacaoTurmaRepositoryIT) deixaria linhas de outros tenants para tras.
        try (Connection conexaoSuperuser = postgres.createConnection("")) {
            conexaoSuperuser.createStatement().execute("TRUNCATE TABLE ocupacao_turma");
        }
    }

    @Test
    void consultarRetornaTodasAsTurmasDoMunicipio() {
        seedTurma(1L, 1000L, 100L, 5, 1L);
        seedTurma(1L, 1001L, 101L, 6, 1L);

        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity("/api/v1/relatorios/ocupacao?municipioId=1", OcupacaoTurmaDTO[].class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactlyInAnyOrder(1000L, 1001L);
    }

    @Test
    void consultarComCrecheIdRetornaSoAsTurmasDaquelaCreche() {
        seedTurma(1L, 1010L, 102L, 5, 1L);
        seedTurma(1L, 1011L, 103L, 6, 1L);

        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/ocupacao?municipioId=1&crecheId=102", OcupacaoTurmaDTO[].class);

        assertThat(resposta.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactly(1010L);
    }

    @Test
    void consultarComTurmaIdRetornaSoAquelaTurma() {
        seedTurma(1L, 1020L, 104L, 5, 1L);
        seedTurma(1L, 1021L, 104L, 6, 1L);

        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/ocupacao?municipioId=1&turmaId=1020", OcupacaoTurmaDTO[].class);

        assertThat(resposta.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactly(1020L);
    }

    @Test
    void consultarComTurmaIdInexistenteDentroDeMunicipioPopuladoRetornaListaVazia() {
        seedTurma(1L, 1025L, 105L, 5, 1L);

        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/ocupacao?municipioId=1&turmaId=9999999", OcupacaoTurmaDTO[].class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).isEmpty();
    }

    @Test
    void consultarSemMunicipioIdRetorna400() {
        ResponseEntity<String> resposta =
                restTemplate.getForEntity("/api/v1/relatorios/ocupacao", String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        assertThat(resposta.getBody()).contains("\"status\":400").contains("\"error\":\"Bad Request\"");
    }

    @Test
    void consultarComMunicipioIdNaoNumericoRetorna400() {
        ResponseEntity<String> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/ocupacao?municipioId=abc", String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        assertThat(resposta.getBody()).contains("\"status\":400").contains("\"error\":\"Bad Request\"");
    }

    @Test
    void consultarSemMatchRetorna200ComListaVazia() {
        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity("/api/v1/relatorios/ocupacao?municipioId=999", OcupacaoTurmaDTO[].class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).isEmpty();
    }

    @Test
    void consultarComCrecheIdDeOutroMunicipioRetornaListaVazia() {
        seedTurma(2L, 1030L, 200L, 5, 1L);

        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/ocupacao?municipioId=1&crecheId=200", OcupacaoTurmaDTO[].class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).isEmpty();
    }

    @Test
    void consultarComTurmaIdDeOutroMunicipioRetornaListaVazia() {
        seedTurma(2L, 1031L, 201L, 5, 1L);

        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/ocupacao?municipioId=1&turmaId=1031", OcupacaoTurmaDTO[].class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).isEmpty();
    }

    @Test
    void consultarEmSequenciaComMunicipiosDiferentesNaoMisturaDados() {
        seedTurma(1L, 1040L, 300L, 5, 1L);
        seedTurma(2L, 1041L, 301L, 6, 1L);

        ResponseEntity<OcupacaoTurmaDTO[]> respostaMunicipio1 =
                restTemplate.getForEntity("/api/v1/relatorios/ocupacao?municipioId=1", OcupacaoTurmaDTO[].class);
        ResponseEntity<OcupacaoTurmaDTO[]> respostaMunicipio2 =
                restTemplate.getForEntity("/api/v1/relatorios/ocupacao?municipioId=2", OcupacaoTurmaDTO[].class);

        assertThat(respostaMunicipio1.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactly(1040L);
        assertThat(respostaMunicipio2.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactly(1041L);
    }

    private void seedTurma(
            Long municipioId, Long turmaId, Long crecheId, Integer vagas, Long eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            repository.upsert(turmaId, municipioId, crecheId, vagas, eventId);
        });
    }
}
