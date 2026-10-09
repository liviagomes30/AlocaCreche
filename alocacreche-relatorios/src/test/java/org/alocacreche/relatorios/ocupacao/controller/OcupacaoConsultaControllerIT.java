package org.alocacreche.relatorios.ocupacao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.util.List;
import org.alocacreche.relatorios.ocupacao.dto.OcupacaoTurmaDTO;
import org.alocacreche.relatorios.ocupacao.repository.OcupacaoTurmaRepository;
import org.alocacreche.relatorios.seguranca.TokenTeste;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cobre RELOC-01 a RELOC-06 via HTTP real (Testcontainers Postgres) e a protecao por permissao
 * do endpoint (AUTH-11 a AUTH-14 e AUTH-17): 200 com a permissao ou com o curinga, 403 sem a
 * permissao ou com municipio diferente do token, 401 sem token valido.
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

    private static final String URL = "/api/v1/relatorios/ocupacao?municipioId=1";
    private static final String PERMISSAO = "relatorios:ocupacao:list";

    @Autowired private TestRestTemplate restTemplate;
    @MockitoSpyBean private OcupacaoTurmaRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(1L, List.of(PERMISSAO)));
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
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(999L, List.of(PERMISSAO)));
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
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(2L, List.of(PERMISSAO)));
        ResponseEntity<OcupacaoTurmaDTO[]> respostaMunicipio2 =
                restTemplate.getForEntity("/api/v1/relatorios/ocupacao?municipioId=2", OcupacaoTurmaDTO[].class);

        assertThat(respostaMunicipio1.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactly(1040L);
        assertThat(respostaMunicipio2.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactly(1041L);
    }

    @Test
    void consultarComAPermissaoDoRelatorioRetorna200() {
        seedTurma(1L, 1050L, 400L, 5, 1L);

        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity(URL, OcupacaoTurmaDTO[].class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).extracting(OcupacaoTurmaDTO::turmaId).containsExactly(1050L);
    }

    @Test
    void consultarComACuringaRetorna200() {
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(1L, List.of("*")));

        ResponseEntity<OcupacaoTurmaDTO[]> resposta =
                restTemplate.getForEntity(URL, OcupacaoTurmaDTO[].class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void consultarComTokenSemAPermissaoRetorna403ComCorpoJsonDeErro() {
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(1L, List.of("relatorios:fila:list")));

        ResponseEntity<String> resposta = restTemplate.getForEntity(URL, String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resposta.getBody()).contains("\"erro\"");
    }

    @Test
    void consultarSemTokenRetorna401ComCorpoJsonDeErro() {
        TokenTeste.usarToken(restTemplate, null);

        ResponseEntity<String> resposta = restTemplate.getForEntity(URL, String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getBody()).contains("\"erro\"");
    }

    @Test
    void consultarComTokenMalformadoRetorna401() {
        TokenTeste.usarToken(restTemplate, "isto-nao-e-um-jwt");

        ResponseEntity<String> resposta = restTemplate.getForEntity(URL, String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getBody()).contains("\"erro\"");
    }

    @Test
    void consultarComTokenDeAssinaturaInvalidaRetorna401() {
        TokenTeste.usarToken(restTemplate, TokenTeste.comAssinaturaInvalida(1L, List.of(PERMISSAO)));

        ResponseEntity<String> resposta = restTemplate.getForEntity(URL, String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getBody()).contains("\"erro\"");
    }

    @Test
    void consultarComTokenExpiradoRetorna401() {
        TokenTeste.usarToken(restTemplate, TokenTeste.expirado(1L, List.of(PERMISSAO)));

        ResponseEntity<String> resposta = restTemplate.getForEntity(URL, String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getBody()).contains("\"erro\"");
    }

    @Test
    void consultarMunicipioDiferenteDoTokenRetorna403SemConsultarOBanco() {
        ResponseEntity<String> resposta =
                restTemplate.getForEntity("/api/v1/relatorios/ocupacao?municipioId=2", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resposta.getBody()).contains("\"erro\"");
        verifyNoInteractions(repository);
    }

    @Test
    void consultarComTokenSemMunicipioRetorna403SemConsultarOBanco() {
        TokenTeste.usarToken(restTemplate, TokenTeste.validoSemMunicipio(List.of(PERMISSAO)));

        ResponseEntity<String> resposta = restTemplate.getForEntity(URL, String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(repository);
    }

    private void seedTurma(
            Long municipioId, Long turmaId, Long crecheId, Integer vagas, Long eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            repository.upsert(turmaId, municipioId, crecheId, vagas, eventId);
        });
    }
}
