package org.alocacreche.core.matricula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.OutboxEvent;
import org.alocacreche.core.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prova ponta a ponta {@code POST /api/v1/matricula/creches} e {@code /turmas} com Postgres real
 * (PROD-08, PROD-09, PROD-10, PROD-18): criacao, evento {@code vaga.liberada} na Outbox, erros 400
 * e 404, 401, 403 e isolamento entre municipios.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("POST /api/v1/matricula/creches e /turmas ponta a ponta")
class CrecheTurmaControllerIT {

    private static final String CAMINHO_CRECHES = "/api/v1/matricula/creches";
    private static final String CAMINHO_TURMAS = "/api/v1/matricula/turmas";

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StpLogicJwtForStateless stpLogic;
    @Autowired private MunicipioRepository municipioRepository;
    @MockitoSpyBean private CrecheRepository crecheRepository;
    @MockitoSpyBean private TurmaRepository turmaRepository;
    @MockitoSpyBean private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private Long municipioA;
    private Long municipioB;

    @BeforeAll
    void prepararMunicipios() {
        municipioA = criarMunicipio();
        municipioB = criarMunicipio();
    }

    @BeforeEach
    void limparOutbox() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("criar creche com token * devolve 201 com crecheId e grava a creche no municipio do token")
    void criarCrecheDevolve201() throws Exception {
        ResponseEntity<String> resposta = postar(CAMINHO_CRECHES, corpoCreche(), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long crecheId = objectMapper.readTree(resposta.getBody()).get("crecheId").asLong();
        emTenant(municipioA, () -> {
            Creche creche = crecheRepository.findById(crecheId).orElseThrow();
            assertThat(creche.getNome()).isEqualTo("Creche Sol");
            assertThat(creche.getCapacidadeTotal()).isEqualTo(50);
            assertThat(creche.getMunicipioId()).isEqualTo(municipioA);
            assertThat(creche.getEndereco().getLatitude()).isEqualTo(-23.56);
            return null;
        });
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("criar turma devolve 201, vagasDisponiveis igual a vagasTotais e uma linha vaga.liberada na Outbox")
    void criarTurmaDevolve201EGravaVagaLiberada() throws Exception {
        Long crecheId = criarCrechePorHttp(municipioA);

        ResponseEntity<String> resposta =
                postar(CAMINHO_TURMAS, corpoTurma(crecheId, 5), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long turmaId = objectMapper.readTree(resposta.getBody()).get("turmaId").asLong();
        emTenant(municipioA, () -> {
            Turma turma = turmaRepository.findById(turmaId).orElseThrow();
            assertThat(turma.getVagasTotais()).isEqualTo(5);
            assertThat(turma.getVagasDisponiveis()).isEqualTo(5);
            assertThat(turma.getFaixaEtaria()).isEqualTo(FaixaEtaria.MATERNAL);
            return null;
        });

        List<OutboxEvent> eventos = outboxEventRepository.findAll();
        assertThat(eventos).hasSize(1);
        assertThat(eventos.get(0).getTipoEvento()).isEqualTo("vaga.liberada");
        assertThat(eventos.get(0).getStatus()).isEqualTo(OutboxEvent.STATUS_PENDENTE);
        JsonNode json = objectMapper.readTree(eventos.get(0).getPayload());
        List<String> nomes = new ArrayList<>();
        json.fieldNames().forEachRemaining(nomes::add);
        assertThat(nomes)
                .containsExactlyInAnyOrder(
                        "tipoEvento", "municipioId", "turmaId", "crecheId", "vagasDisponiveis", "ocorridoEm");
        assertThat(json.get("tipoEvento").asText()).isEqualTo("vaga.liberada");
        assertThat(json.get("municipioId").asLong()).isEqualTo(municipioA);
        assertThat(json.get("turmaId").asLong()).isEqualTo(turmaId);
        assertThat(json.get("crecheId").asLong()).isEqualTo(crecheId);
        assertThat(json.get("vagasDisponiveis").asInt()).isEqualTo(5);
        assertThat(json.get("ocorridoEm").isTextual()).isTrue();
    }

    @Test
    @DisplayName("falha ao gravar vaga.liberada desfaz a criacao da turma: nenhuma turma e nenhuma linha na Outbox")
    void falhaNoEventoDesfazACriacaoDaTurma() {
        Long crecheId = criarCrechePorHttp(municipioA);
        doThrow(new IllegalStateException("falha ao gravar vaga.liberada"))
                .when(outboxEventRepository)
                .save(any(OutboxEvent.class));

        ResponseEntity<String> resposta =
                postar(CAMINHO_TURMAS, corpoTurma(crecheId, 5), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode().is5xxServerError()).isTrue();
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(turmaRepository.findByCrecheId(crecheId)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("crecheId inexistente responde 404 sem criar turma nem evento")
    void crecheInexistenteResponde404() {
        ResponseEntity<String> resposta =
                postar(CAMINHO_TURMAS, corpoTurma(999_999L, 5), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("vagasTotais zero responde 400 sem criar turma nem evento")
    void vagasTotaisZeroResponde400() {
        Long crecheId = criarCrechePorHttp(municipioA);

        ResponseEntity<String> resposta =
                postar(CAMINHO_TURMAS, corpoTurma(crecheId, 0), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("vagasTotais negativo responde 400 sem criar turma nem evento")
    void vagasTotaisNegativoResponde400() {
        Long crecheId = criarCrechePorHttp(municipioA);

        ResponseEntity<String> resposta =
                postar(CAMINHO_TURMAS, corpoTurma(crecheId, -3), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("turma sem faixaEtaria responde 400 com o campo na lista")
    void turmaSemCampoObrigatorioResponde400() throws Exception {
        Long crecheId = criarCrechePorHttp(municipioA);
        Map<String, Object> corpo = corpoTurma(crecheId, 5);
        corpo.remove("faixaEtaria");

        ResponseEntity<String> resposta = postar(CAMINHO_TURMAS, corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(resposta.getBody()).get("campos").get(0).asText())
                .isEqualTo("faixaEtaria");
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("creche sem nome responde 400 com o campo na lista")
    void crecheSemNomeResponde400() throws Exception {
        Map<String, Object> corpo = corpoCreche();
        corpo.remove("nome");

        ResponseEntity<String> resposta = postar(CAMINHO_CRECHES, corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(resposta.getBody()).get("campos").get(0).asText()).isEqualTo("nome");
    }

    @Test
    @DisplayName("creche sem nome responde 400 sem consultar o banco")
    void crecheSemNomeNaoConsultaOBanco() {
        Map<String, Object> corpo = corpoCreche();
        corpo.remove("nome");
        clearInvocations(crecheRepository, turmaRepository, outboxEventRepository);

        ResponseEntity<String> resposta = postar(CAMINHO_CRECHES, corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(crecheRepository, turmaRepository, outboxEventRepository);
    }

    @Test
    @DisplayName("turma sem faixaEtaria responde 400 sem consultar o banco")
    void turmaSemFaixaEtariaNaoConsultaOBanco() {
        Long crecheId = criarCrechePorHttp(municipioA);
        Map<String, Object> corpo = corpoTurma(crecheId, 5);
        corpo.remove("faixaEtaria");
        clearInvocations(crecheRepository, turmaRepository, outboxEventRepository);

        ResponseEntity<String> resposta = postar(CAMINHO_TURMAS, corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(crecheRepository, turmaRepository, outboxEventRepository);
    }

    @Test
    @DisplayName("JSON ilegivel em creches e em turmas responde 400 sem consultar o banco")
    void jsonIlegivelResponde400SemConsultarOBanco() {
        String token = tokenCom(municipioA, List.of("*"));
        clearInvocations(crecheRepository, turmaRepository, outboxEventRepository);

        ResponseEntity<String> creche = postarTexto(CAMINHO_CRECHES, "{isto nao e json", token);
        ResponseEntity<String> turma = postarTexto(CAMINHO_TURMAS, "{isto nao e json", token);

        assertThat(creche.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(turma.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(crecheRepository, turmaRepository, outboxEventRepository);
    }

    @Test
    @DisplayName("sem token os dois endpoints respondem 401")
    void semTokenResponde401() {
        assertThat(postar(CAMINHO_CRECHES, corpoCreche(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postar(CAMINHO_TURMAS, corpoTurma(1L, 5), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("token sem a permissao especifica de cada endpoint responde 403")
    void tokenSemPermissaoResponde403() {
        Long crecheId = criarCrechePorHttp(municipioA);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> creche =
                postar(CAMINHO_CRECHES, corpoCreche(), tokenCom(municipioA, List.of("matricula:turma:add")));
        ResponseEntity<String> turma =
                postar(CAMINHO_TURMAS, corpoTurma(crecheId, 5), tokenCom(municipioA, List.of("matricula:creche:add")));

        assertThat(creche.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(turma.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("tokens com exatamente a permissao de cada endpoint respondem 201")
    void tokenComAPermissaoEspecificaResponde201() {
        ResponseEntity<String> creche =
                postar(CAMINHO_CRECHES, corpoCreche(), tokenCom(municipioA, List.of("matricula:creche:add")));
        Long crecheId = idDe(creche, "crecheId");
        ResponseEntity<String> turma =
                postar(CAMINHO_TURMAS, corpoTurma(crecheId, 5), tokenCom(municipioA, List.of("matricula:turma:add")));

        assertThat(creche.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(turma.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("o municipio 2 nao enxerga a creche do municipio 1 e responde 404")
    void municipioBNaoEnxergaCrecheDoMunicipioA() {
        Long crecheDoA = criarCrechePorHttp(municipioA);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta =
                postar(CAMINHO_TURMAS, corpoTurma(crecheDoA, 5), tokenCom(municipioB, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    private Long criarCrechePorHttp(Long municipioId) {
        return idDe(postar(CAMINHO_CRECHES, corpoCreche(), tokenCom(municipioId, List.of("*"))), "crecheId");
    }

    private Long idDe(ResponseEntity<String> resposta, String campo) {
        try {
            return objectMapper.readTree(resposta.getBody()).get(campo).asLong();
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private ResponseEntity<String> postar(String caminho, Map<String, Object> corpo, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return restTemplate.exchange(
                    caminho,
                    HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(corpo), headers),
                    String.class);
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private ResponseEntity<String> postarTexto(String caminho, String corpo, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return restTemplate.exchange(caminho, HttpMethod.POST, new HttpEntity<>(corpo, headers), String.class);
    }

    private String tokenCom(Long municipioId, List<String> permissoes) {
        return stpLogic.createTokenValue(
                1L, "default", 3600, Map.of("municipioId", municipioId, "perms", permissoes));
    }

    private Map<String, Object> corpoCreche() {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("nome", "Creche Sol");
        corpo.put("capacidade", 50);
        corpo.put(
                "endereco",
                Map.of(
                        "rua", "Rua B",
                        "numero", 20,
                        "bairro", "Centro",
                        "cidade", "Sao Paulo",
                        "estado", "SP",
                        "latitude", -23.56,
                        "longitude", -46.64));
        return corpo;
    }

    private Map<String, Object> corpoTurma(Long crecheId, int vagasTotais) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("crecheId", crecheId);
        corpo.put("faixaEtaria", "MATERNAL");
        corpo.put("vagasTotais", vagasTotais);
        return corpo;
    }

    private <T> T emTenant(Long municipioId, Supplier<T> acao) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            TenantContext.aplicar(entityManager, municipioId);
                            return acao.get();
                        });
    }

    private Long criarMunicipio() {
        Municipio municipio = new Municipio();
        municipio.setNome("Municipio de Teste");
        municipio.setUf("SP");
        municipio.setPrazoConfirmacaoDias(5);
        municipio.setConfiguracaoPriorizacao(
                ConfiguracaoPriorizacao.builder()
                        .pesoRenda(0.4)
                        .pesoVulnerabilidade(0.3)
                        .pesoDistancia(0.2)
                        .pesoIrmaoMatriculado(0.1)
                        .build());
        return municipioRepository.saveAndFlush(municipio).getId();
    }
}
