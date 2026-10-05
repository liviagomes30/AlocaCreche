package org.alocacreche.relatorios.filaespera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
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
 * Cobre FILA-10 a FILA-15 via HTTP real (Testcontainers Postgres) e a protecao por permissao do
 * endpoint (AUTH-11, AUTH-12, AUTH-13 e AUTH-17): 200 com a permissao, 403 sem a permissao ou com
 * municipio diferente do token, 401 sem token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RelatorioFilaEsperaControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    private static final String URL = "/api/v1/relatorios/fila-espera?municipioId=1";
    private static final String PERMISSAO = "relatorios:fila:list";

    @Autowired private TestRestTemplate restTemplate;

    @MockitoSpyBean private FilaEsperaInscricaoRepository repository;

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
        // deixaria linhas de outros tenants para tras (mesmo padrao de OcupacaoConsultaControllerIT).
        try (Connection conexaoSuperuser = postgres.createConnection("")) {
            conexaoSuperuser.createStatement().execute("TRUNCATE TABLE fila_espera_inscricao");
        }
    }

    @Test
    void consultarComUmaInscricaoAguardandoRetornaContagemUmETempoMedioMaiorQueZero() {
        seedInscricao(1L, 5000L, 700L, 70L, LocalDate.now().minusDays(3), null, 1L);

        ResponseEntity<RelatorioFilaEsperaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=1", RelatorioFilaEsperaDTO[].class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).hasSize(1);
        RelatorioFilaEsperaDTO linha = resposta.getBody()[0];
        assertThat(linha.turmaId()).isEqualTo(700L);
        assertThat(linha.crecheId()).isEqualTo(70L);
        assertThat(linha.municipioId()).isEqualTo(1L);
        assertThat(linha.quantidadeAguardando()).isEqualTo(1L);
        assertThat(linha.tempoMedioEsperaDias()).isGreaterThan(0.0);
    }

    @Test
    void consultarSemMunicipioIdRetorna400SemConsultarRepositorio() {
        ResponseEntity<String> resposta =
                restTemplate.getForEntity("/api/v1/relatorios/fila-espera", String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        assertThat(resposta.getBody()).contains("\"status\":400").contains("\"error\":\"Bad Request\"");
        // FILA-11 exige "sem consultar o banco", nao so o codigo de status - o spy prova que o
        // repositorio nunca foi tocado, nao so que o Spring MVC devolveu 400.
        verifyNoInteractions(repository);
    }

    @Test
    void consultarComCrecheIdRetornaSoAsTurmasDaquelaCreche() {
        seedInscricao(1L, 5010L, 710L, 71L, LocalDate.now().minusDays(1), null, 1L);
        seedInscricao(1L, 5011L, 711L, 72L, LocalDate.now().minusDays(1), null, 1L);

        ResponseEntity<RelatorioFilaEsperaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=1&crecheId=71",
                        RelatorioFilaEsperaDTO[].class);

        assertThat(resposta.getBody()).extracting(RelatorioFilaEsperaDTO::turmaId).containsExactly(710L);
    }

    @Test
    void consultarComTurmaIdRetornaSoAquelaTurma() {
        seedInscricao(1L, 5020L, 720L, 73L, LocalDate.now().minusDays(1), null, 1L);
        seedInscricao(1L, 5021L, 721L, 73L, LocalDate.now().minusDays(1), null, 1L);

        ResponseEntity<RelatorioFilaEsperaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=1&turmaId=720",
                        RelatorioFilaEsperaDTO[].class);

        assertThat(resposta.getBody()).extracting(RelatorioFilaEsperaDTO::turmaId).containsExactly(720L);
    }

    @Test
    void consultarTurmaSemNenhumEventoNaoApareceNaLista() {
        seedInscricao(1L, 5030L, 730L, 74L, LocalDate.now().minusDays(1), null, 1L);

        ResponseEntity<RelatorioFilaEsperaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=1&turmaId=999999",
                        RelatorioFilaEsperaDTO[].class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).isEmpty();
    }

    @Test
    void chamarDuasVezesSeguidasSemNovoEventoTempoMedioNaoDiminui() {
        seedInscricao(1L, 5040L, 740L, 75L, LocalDate.now().minusDays(5), null, 1L);

        ResponseEntity<RelatorioFilaEsperaDTO[]> primeira =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=1&turmaId=740",
                        RelatorioFilaEsperaDTO[].class);
        ResponseEntity<RelatorioFilaEsperaDTO[]> segunda =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=1&turmaId=740",
                        RelatorioFilaEsperaDTO[].class);

        double tempoMedioPrimeiraChamada = primeira.getBody()[0].tempoMedioEsperaDias();
        double tempoMedioSegundaChamada = segunda.getBody()[0].tempoMedioEsperaDias();
        assertThat(tempoMedioSegundaChamada).isGreaterThanOrEqualTo(tempoMedioPrimeiraChamada);
    }

    @Test
    void respostaEDTOSemCamposDaEntidadeOuDaProjecaoInterna() {
        seedInscricao(1L, 5050L, 750L, 76L, LocalDate.now().minusDays(2), null, 1L);

        ResponseEntity<String> resposta =
                restTemplate.getForEntity("/api/v1/relatorios/fila-espera?municipioId=1&turmaId=750", String.class);

        // Corpo HTTP inspecionado como String, nao so o tipo de retorno do metodo Java (FILA-13) -
        // so os 5 campos do RelatorioFilaEsperaDTO aparecem, nenhum campo interno da entidade
        // (ultimaSequenciaAplicada/atualizadoEm) ou da projecao.
        assertThat(resposta.getBody())
                .contains("\"turmaId\"")
                .contains("\"crecheId\"")
                .contains("\"municipioId\"")
                .contains("\"quantidadeAguardando\"")
                .contains("\"tempoMedioEsperaDias\"")
                .doesNotContain("ultimaSequenciaAplicada")
                .doesNotContain("atualizadoEm")
                .doesNotContain("inscricaoId")
                .doesNotContain("dataEntrada")
                .doesNotContain("dataSaida");
    }

    @Test
    void consultarComCrecheIdDeOutroMunicipioRetornaListaVazia() {
        seedInscricao(2L, 5060L, 760L, 900L, LocalDate.now().minusDays(1), null, 1L);

        ResponseEntity<RelatorioFilaEsperaDTO[]> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=1&crecheId=900",
                        RelatorioFilaEsperaDTO[].class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).isEmpty();
    }

    @Test
    void consultarEmSequenciaComMunicipiosDiferentesNaoMisturaDados() {
        seedInscricao(1L, 5070L, 770L, 77L, LocalDate.now().minusDays(1), null, 1L);
        seedInscricao(2L, 5071L, 771L, 78L, LocalDate.now().minusDays(1), null, 1L);

        ResponseEntity<RelatorioFilaEsperaDTO[]> respostaMunicipio1 =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=1", RelatorioFilaEsperaDTO[].class);
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(2L, List.of(PERMISSAO)));
        ResponseEntity<RelatorioFilaEsperaDTO[]> respostaMunicipio2 =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/fila-espera?municipioId=2", RelatorioFilaEsperaDTO[].class);

        assertThat(respostaMunicipio1.getBody()).extracting(RelatorioFilaEsperaDTO::turmaId).containsExactly(770L);
        assertThat(respostaMunicipio2.getBody()).extracting(RelatorioFilaEsperaDTO::turmaId).containsExactly(771L);
    }

    @Test
    void consultarComAPermissaoDoRelatorioRetorna200() {
        seedInscricao(1L, 5080L, 780L, 79L, LocalDate.now().minusDays(1), null, 1L);

        ResponseEntity<RelatorioFilaEsperaDTO[]> resposta =
                restTemplate.getForEntity(URL, RelatorioFilaEsperaDTO[].class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).extracting(RelatorioFilaEsperaDTO::turmaId).containsExactly(780L);
    }

    @Test
    void consultarComTokenSemAPermissaoRetorna403ComCorpoJsonDeErro() {
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(1L, List.of("relatorios:ocupacao:list")));

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
    void consultarMunicipioDiferenteDoTokenRetorna403SemConsultarOBanco() {
        ResponseEntity<String> resposta =
                restTemplate.getForEntity("/api/v1/relatorios/fila-espera?municipioId=2", String.class);

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

    private void seedInscricao(
            Long municipioId,
            Long inscricaoId,
            Long turmaId,
            Long crecheId,
            LocalDate dataEntrada,
            LocalDate dataSaida,
            Long eventId) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    TenantContext.aplicar(entityManager, municipioId);
                    repository.upsert(inscricaoId, turmaId, crecheId, municipioId, dataEntrada, dataSaida, eventId);
                });
    }
}
