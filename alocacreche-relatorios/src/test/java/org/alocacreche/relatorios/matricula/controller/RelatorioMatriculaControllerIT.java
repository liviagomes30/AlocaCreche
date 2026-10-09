package org.alocacreche.relatorios.matricula.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import org.alocacreche.relatorios.matricula.domain.StatusMatricula;
import org.alocacreche.relatorios.matricula.dto.DistanciaMediaAlocacaoDTO;
import org.alocacreche.relatorios.matricula.dto.RelatorioConfirmacaoMatriculaDTO;
import org.alocacreche.relatorios.matricula.repository.MatriculaRelatorioRepository;
import org.alocacreche.relatorios.seguranca.TokenTeste;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Cobre os dois endpoints via HTTP real (Testcontainers Postgres) - mesmo padrao de
 * RelatorioFilaEsperaControllerIT/OcupacaoConsultaControllerIT - e a protecao por permissao dos dois
 * endpoints (AUTH-11, AUTH-12, AUTH-13 e AUTH-17): 200 com a permissao, 403 sem a permissao ou com
 * municipio diferente do token, 401 sem token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RelatorioMatriculaControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    private static final String DISTANCIA_MEDIA = "distancia-media";
    private static final String TAXA_CONFIRMACAO = "taxa-confirmacao";
    private static final String PERMISSAO = "relatorios:matricula:list";

    @Autowired private TestRestTemplate restTemplate;

    @MockitoSpyBean private MatriculaRelatorioRepository repository;

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
        try (Connection conexaoSuperuser = postgres.createConnection("")) {
            conexaoSuperuser.createStatement().execute("TRUNCATE TABLE matricula_relatorio");
        }
    }

    @Test
    void distanciaMediaComDuasMatriculasNoPeriodoRetornaMedia() {
        LocalDate periodo = LocalDate.of(2026, 5, 1);
        seedMatricula(1L, 930L, 600L, 200L, 20L, StatusMatricula.PENDENTE, 2.0, LocalDate.of(2026, 5, 3), null, 1L);
        seedMatricula(1L, 931L, 601L, 200L, 20L, StatusMatricula.PENDENTE, 6.0, LocalDate.of(2026, 5, 15), null, 1L);

        ResponseEntity<DistanciaMediaAlocacaoDTO> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/matricula/distancia-media?municipioId=1&periodo=2026-05-01",
                        DistanciaMediaAlocacaoDTO.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody().distanciaMediaKm()).isCloseTo(4.0, within(0.001));
    }

    @Test
    void distanciaMediaSemMunicipioIdRetorna400SemConsultarRepositorio() {
        ResponseEntity<String> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/matricula/distancia-media?periodo=2026-05-01", String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(repository);
    }

    @Test
    void taxaConfirmacaoComUmaConfirmadaEUmaPendenteRetornaMetade() {
        LocalDate periodo = LocalDate.of(2026, 6, 1);
        seedMatricula(
                1L, 940L, 610L, 210L, 21L, StatusMatricula.CONFIRMADA, 1.0, LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 5), 1L);
        seedMatricula(1L, 941L, 611L, 210L, 21L, StatusMatricula.PENDENTE, 1.0, LocalDate.of(2026, 6, 3), null, 1L);

        ResponseEntity<RelatorioConfirmacaoMatriculaDTO> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/matricula/taxa-confirmacao?municipioId=1&periodo=2026-06-01",
                        RelatorioConfirmacaoMatriculaDTO.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody().totalMatriculas()).isEqualTo(2L);
        assertThat(resposta.getBody().totalConfirmadas()).isEqualTo(1L);
        assertThat(resposta.getBody().taxaConfirmacao()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void taxaConfirmacaoSemMatriculaNoPeriodoRetornaTaxaNula() {
        ResponseEntity<RelatorioConfirmacaoMatriculaDTO> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/matricula/taxa-confirmacao?municipioId=1&periodo=2026-07-01",
                        RelatorioConfirmacaoMatriculaDTO.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody().totalMatriculas()).isEqualTo(0L);
        assertThat(resposta.getBody().taxaConfirmacao()).isNull();
    }

    @Test
    void distanciaMediaNaoMisturaDadosDeOutroMunicipio() {
        seedMatricula(2L, 950L, 620L, 220L, 900L, StatusMatricula.PENDENTE, 9.0, LocalDate.of(2026, 5, 4), null, 1L);

        ResponseEntity<DistanciaMediaAlocacaoDTO> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/matricula/distancia-media?municipioId=1&periodo=2026-05-01",
                        DistanciaMediaAlocacaoDTO.class);

        assertThat(resposta.getBody().distanciaMediaKm()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {DISTANCIA_MEDIA, TAXA_CONFIRMACAO})
    void consultarComAPermissaoDoRelatorioRetorna200(String endpoint) {
        ResponseEntity<String> resposta = restTemplate.getForEntity(url(endpoint, 1L), String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {DISTANCIA_MEDIA, TAXA_CONFIRMACAO})
    void consultarComTokenSemAPermissaoRetorna403ComCorpoJsonDeErro(String endpoint) {
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(1L, List.of("relatorios:fila:list")));

        ResponseEntity<String> resposta = restTemplate.getForEntity(url(endpoint, 1L), String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resposta.getBody()).contains("\"erro\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {DISTANCIA_MEDIA, TAXA_CONFIRMACAO})
    void consultarSemTokenRetorna401ComCorpoJsonDeErro(String endpoint) {
        TokenTeste.usarToken(restTemplate, null);

        ResponseEntity<String> resposta = restTemplate.getForEntity(url(endpoint, 1L), String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getBody()).contains("\"erro\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {DISTANCIA_MEDIA, TAXA_CONFIRMACAO})
    void consultarMunicipioDiferenteDoTokenRetorna403SemConsultarOBanco(String endpoint) {
        ResponseEntity<String> resposta = restTemplate.getForEntity(url(endpoint, 2L), String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resposta.getBody()).contains("\"erro\"");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {DISTANCIA_MEDIA, TAXA_CONFIRMACAO})
    void consultarComTokenSemMunicipioRetorna403SemConsultarOBanco(String endpoint) {
        TokenTeste.usarToken(restTemplate, TokenTeste.validoSemMunicipio(List.of(PERMISSAO)));

        ResponseEntity<String> resposta = restTemplate.getForEntity(url(endpoint, 1L), String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(repository);
    }

    private static String url(String endpoint, Long municipioId) {
        return "/api/v1/relatorios/matricula/" + endpoint + "?municipioId=" + municipioId + "&periodo=2026-05-01";
    }

    private void seedMatricula(
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
}
