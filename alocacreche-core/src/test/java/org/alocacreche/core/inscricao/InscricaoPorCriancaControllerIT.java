package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prova ponta a ponta, com Postgres real e RLS, o endpoint da Stack A que registra a inscricao de
 * uma crianca ja cadastrada e calcula o score de prioridade: permissao, municipio vindo do token,
 * isolamento entre municipios e resposta sem entidade JPA.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("POST /api/v1/inscricao/inscricoes/por-crianca ponta a ponta")
class InscricaoPorCriancaControllerIT {

    private static final String CAMINHO = "/api/v1/inscricao/inscricoes/por-crianca";
    private static final String PERMISSAO = "inscricao:inscricao:add";

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
    @Autowired private ResponsavelRepository responsavelRepository;
    @Autowired private InscricaoRepository inscricaoRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final AtomicInteger sequenciaCpf = new AtomicInteger(500);

    private Long municipioA;
    private Long municipioB;

    @BeforeAll
    void prepararMunicipios() {
        municipioA = criarMunicipio();
        municipioB = criarMunicipio();
    }

    @Test
    @DisplayName("renda 400 e vulnerabilidade somam 0,4 + 0,3 e devolvem 201 sem entidade JPA")
    void inscricaoValidaDevolve201ComScore() throws Exception {
        Long criancaId = criarCrianca(municipioA);

        ResponseEntity<String> resposta =
                postar(corpo(municipioA, criancaId, "400.00", true), tokenCom(municipioA, List.of(PERMISSAO)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(resposta.getBody());
        long inscricaoId = json.get("inscricaoId").asLong();
        assertThat(json.get("scorePrioridade").asDouble()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(json.get("status").asText()).isEqualTo("AGUARDANDO");
        assertThat(json.has("municipio")).isFalse();
        assertThat(json.has("crianca")).isFalse();
        emTenant(municipioA, () -> {
            Inscricao salva = inscricaoRepository.findById(inscricaoId).orElseThrow();
            assertThat(salva.getScorePrioridade()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(salva.getCriterioVulnerabilidade()).isTrue();
            assertThat(salva.getRendaPerCapita()).isEqualByComparingTo("400.00");
            return null;
        });
    }

    @Test
    @DisplayName("renda 1200 sem vulnerabilidade soma so o fator de renda 0,5 x 0,4")
    void rendaMediaSemVulnerabilidade() throws Exception {
        Long criancaId = criarCrianca(municipioA);

        ResponseEntity<String> resposta =
                postar(corpo(municipioA, criancaId, "1200.00", false), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(resposta.getBody()).get("scorePrioridade").asDouble())
                .isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("sem token responde 401")
    void semTokenResponde401() {
        ResponseEntity<String> resposta = postar(corpo(municipioA, 1L, "400.00", true), null);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("token sem a permissao inscricao:inscricao:add responde 403")
    void tokenSemPermissaoResponde403() {
        Long criancaId = criarCrianca(municipioA);

        ResponseEntity<String> resposta =
                postar(
                        corpo(municipioA, criancaId, "400.00", true),
                        tokenCom(municipioA, List.of("relatorios:ocupacao:list")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("municipioId do corpo diferente do do token responde 403 e nao grava")
    void municipioDoCorpoDiferenteDoTokenResponde403() {
        Long criancaId = criarCrianca(municipioA);

        ResponseEntity<String> resposta =
                postar(corpo(municipioB, criancaId, "400.00", true), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("crianca inexistente responde 404")
    void criancaInexistenteResponde404() {
        ResponseEntity<String> resposta =
                postar(corpo(municipioA, 999_999L, "400.00", true), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("crianca de outro municipio e invisivel pela RLS e responde 404")
    void criancaDeOutroMunicipioResponde404() {
        Long criancaDoB = criarCrianca(municipioB);

        ResponseEntity<String> resposta =
                postar(corpo(municipioA, criancaDoB, "400.00", true), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("corpo sem criancaId responde 400")
    void corpoSemCriancaIdResponde400() {
        Map<String, Object> corpo = Map.of("municipioId", municipioA, "rendaPerCapita", "400.00");

        ResponseEntity<String> resposta = postar(corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private Map<String, Object> corpo(Long municipioId, Long criancaId, String renda, boolean vulnerabilidade) {
        return Map.of(
                "municipioId", municipioId,
                "criancaId", criancaId,
                "rendaPerCapita", renda,
                "criterioVulnerabilidade", vulnerabilidade);
    }

    private ResponseEntity<String> postar(Map<String, Object> corpo, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return restTemplate.exchange(
                    CAMINHO,
                    HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(corpo), headers),
                    String.class);
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private String tokenCom(Long municipioId, List<String> permissoes) {
        return stpLogic.createTokenValue(
                1L, "default", 3600, Map.of("municipioId", municipioId, "perms", permissoes));
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

    private Long criarCrianca(Long municipioId) {
        return emTenant(municipioId, () -> {
            Responsavel responsavel = new Responsavel();
            responsavel.setMunicipioId(municipioId);
            responsavel.setNome("Responsavel de Teste");
            responsavel.setCpf(String.format("%03d.111.111-11", sequenciaCpf.incrementAndGet()));
            responsavel.setEndereco(new Endereco("Rua A", 10, "Centro", "01000-000", "Sao Paulo", "SP", -23.55, -46.63));
            Crianca crianca = new Crianca();
            crianca.setMunicipioId(municipioId);
            crianca.setNome("Crianca de Teste");
            crianca.setDataNascimento(LocalDate.of(2024, 3, 10));
            responsavel.adicionarCrianca(crianca);
            return responsavelRepository.saveAndFlush(responsavel).getCriancas().get(0).getId();
        });
    }
}
