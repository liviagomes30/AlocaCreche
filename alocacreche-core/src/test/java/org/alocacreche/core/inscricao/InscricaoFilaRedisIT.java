package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.CrecheRepository;
import org.alocacreche.core.matricula.FaixaEtaria;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prova ponta a ponta o cache da fila no Redis real (Testcontainers) junto com Postgres real: a
 * posicao vem do Redis, a chave carrega o municipio, o rollback nao deixa entrada fantasma, a
 * alocacao remove a entrada, a chave ausente cai para o banco, o isolamento entre municipios (404)
 * e as permissoes (401 e 403), alem dos cadastros de responsavel e crianca. O comportamento sem
 * Redis (fail-open) e coberto pelos demais ITs do core, que apontam para uma porta fechada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Fila de espera no Redis e cadastros de Responsavel e Crianca ponta a ponta")
class InscricaoFilaRedisIT {

    private static final String BASE = "/api/v1/inscricao";
    private static final String CAMINHO_INSCRICOES = BASE + "/inscricoes";
    private static final String CAMINHO_RESPONSAVEIS = BASE + "/responsaveis";
    private static final String CAMINHO_CRIANCAS = BASE + "/criancas";
    private static final String CAMINHO_ALOCAR = "/api/v1/matricula/vagas/alocar";

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    static final GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StpLogicJwtForStateless stpLogic;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private MunicipioRepository municipioRepository;
    @Autowired private CrecheRepository crecheRepository;
    @Autowired private TurmaRepository turmaRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private OutboxEventRepository outboxEventRepository;
    @PersistenceContext private EntityManager entityManager;

    private final AtomicInteger sequenciaCpf = new AtomicInteger(100);

    private Long municipioA;
    private Long municipioB;
    private Long crecheDoMunicipioA;
    private Long turmaDoMunicipioA;

    @BeforeAll
    void prepararMunicipios() {
        municipioA = criarMunicipio();
        municipioB = criarMunicipio();
        crecheDoMunicipioA = criarCreche(municipioA);
    }

    @BeforeEach
    void prepararTurma() {
        turmaDoMunicipioA = criarTurma(municipioA, crecheDoMunicipioA);
    }

    @Test
    @DisplayName("duas inscricoes: posicoes 1 e 2 sao lidas do Redis, na chave que carrega o municipio")
    void posicoesVemDoRedisNaChaveComMunicipio() throws Exception {
        long primeira = inscrever(turmaDoMunicipioA);
        long segunda = inscrever(turmaDoMunicipioA);
        String chave = chave(municipioA, turmaDoMunicipioA);

        assertThat(redisTemplate.opsForZSet().score(chave, String.valueOf(primeira))).isEqualTo(1.0);
        assertThat(redisTemplate.opsForZSet().score(chave, String.valueOf(segunda))).isEqualTo(2.0);
        assertThat(redisTemplate.keys("fila:turma:*")).isEmpty();
        assertThat(posicaoPelaApi(municipioA, primeira, turmaDoMunicipioA)).isEqualTo(1);
        assertThat(posicaoPelaApi(municipioA, segunda, turmaDoMunicipioA)).isEqualTo(2);

        redisTemplate.opsForZSet().add(chave, String.valueOf(segunda), 9);
        assertThat(posicaoPelaApi(municipioA, segunda, turmaDoMunicipioA))
                .as("a resposta reflete o Redis, nao o banco")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("resposta da posicao traz inscricaoId, turmaId e posicao")
    void respostaDaPosicaoTemOsTresCampos() throws Exception {
        long inscricaoId = inscrever(turmaDoMunicipioA);

        ResponseEntity<String> resposta = consultarPosicao(municipioA, inscricaoId, turmaDoMunicipioA);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resposta.getBody());
        assertThat(json.get("inscricaoId").asLong()).isEqualTo(inscricaoId);
        assertThat(json.get("turmaId").asLong()).isEqualTo(turmaDoMunicipioA);
        assertThat(json.get("posicao").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("rollback da inscricao nao deixa entrada fantasma no Redis")
    void rollbackNaoDeixaEntradaNoRedis() {
        String chave = chave(municipioA, turmaDoMunicipioA);
        doThrow(new IllegalStateException("falha ao gravar fila.atualizada"))
                .when(outboxEventRepository)
                .save(any(OutboxEvent.class));

        ResponseEntity<String> resposta = enviar(
                HttpMethod.POST, CAMINHO_INSCRICOES, corpoInscricao(novoCpf(), turmaDoMunicipioA), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode().is5xxServerError()).isTrue();
        assertThat(redisTemplate.hasKey(chave)).isFalse();
    }

    @Test
    @DisplayName("alocar a vaga remove a inscricao do Redis e a posicao passa a responder 404")
    void alocarRemoveDoRedis() {
        long inscricaoId = inscrever(turmaDoMunicipioA);
        String chave = chave(municipioA, turmaDoMunicipioA);
        assertThat(redisTemplate.opsForZSet().score(chave, String.valueOf(inscricaoId))).isNotNull();

        ResponseEntity<String> alocacao =
                enviar(HttpMethod.POST, CAMINHO_ALOCAR, Map.of("turmaId", turmaDoMunicipioA), tokenCom(municipioA, List.of("*")));

        assertThat(alocacao.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisTemplate.opsForZSet().score(chave, String.valueOf(inscricaoId))).isNull();
        ResponseEntity<String> posicao = consultarPosicao(municipioA, inscricaoId, turmaDoMunicipioA);
        assertThat(posicao.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(posicao.getBody()).contains("não está na fila");
    }

    @Test
    @DisplayName("sem a chave no Redis (cache frio) a posicao vem da fila persistida no banco")
    void semChaveNoRedisCaiParaOBanco() throws Exception {
        inscrever(turmaDoMunicipioA);
        long segunda = inscrever(turmaDoMunicipioA);
        redisTemplate.delete(chave(municipioA, turmaDoMunicipioA));

        assertThat(posicaoPelaApi(municipioA, segunda, turmaDoMunicipioA)).isEqualTo(2);
    }

    @Test
    @DisplayName("inscricao de outro municipio responde 404, mesmo com a entrada no Redis do outro municipio")
    void inscricaoDeOutroMunicipioResponde404() {
        long inscricaoId = inscrever(turmaDoMunicipioA);

        ResponseEntity<String> resposta = consultarPosicao(municipioB, inscricaoId, turmaDoMunicipioA);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("posicao: sem token responde 401 e sem a permissao inscricao:fila:detail responde 403")
    void posicaoExigeTokenEPermissao() {
        long inscricaoId = inscrever(turmaDoMunicipioA);
        String caminho = CAMINHO_INSCRICOES + "/" + inscricaoId + "/posicao?turmaId=" + turmaDoMunicipioA;

        ResponseEntity<String> semToken = enviar(HttpMethod.GET, caminho, null, null);
        ResponseEntity<String> semPermissao =
                enviar(HttpMethod.GET, caminho, null, tokenCom(municipioA, List.of("inscricao:inscricao:add")));

        assertThat(semToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(semPermissao.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("responsavel: 201 so com responsavelId, sem CPF nem outro dado na resposta")
    void cadastrarResponsavelDevolve201SemDadosSensiveis() throws Exception {
        String cpf = novoCpf();

        ResponseEntity<String> resposta = enviar(
                HttpMethod.POST, CAMINHO_RESPONSAVEIS, corpoResponsavel(cpf), tokenCom(municipioA, List.of("inscricao:responsavel:add")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(resposta.getBody());
        assertThat(json.fieldNames()).toIterable().containsExactly("responsavelId");
        assertThat(json.get("responsavelId").asLong()).isPositive();
        assertThat(resposta.getBody()).doesNotContain(cpf);
    }

    @Test
    @DisplayName("responsavel: CPF repetido, inclusive de outro municipio, responde 409")
    void cadastrarResponsavelComCpfRepetidoResponde409() {
        String cpf = novoCpf();
        String tokenA = tokenCom(municipioA, List.of("*"));
        enviar(HttpMethod.POST, CAMINHO_RESPONSAVEIS, corpoResponsavel(cpf), tokenA);

        ResponseEntity<String> mesmoMunicipio = enviar(HttpMethod.POST, CAMINHO_RESPONSAVEIS, corpoResponsavel(cpf), tokenA);
        ResponseEntity<String> outroMunicipio =
                enviar(HttpMethod.POST, CAMINHO_RESPONSAVEIS, corpoResponsavel(cpf), tokenCom(municipioB, List.of("*")));

        assertThat(mesmoMunicipio.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outroMunicipio.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("responsavel: corpo invalido responde 400 JSON e sem permissao responde 403")
    void cadastrarResponsavelValidaCorpoEPermissao() throws Exception {
        Map<String, Object> invalido = corpoResponsavel(novoCpf());
        invalido.put("nome", " ");

        ResponseEntity<String> resposta =
                enviar(HttpMethod.POST, CAMINHO_RESPONSAVEIS, invalido, tokenCom(municipioA, List.of("*")));
        ResponseEntity<String> semPermissao = enviar(
                HttpMethod.POST, CAMINHO_RESPONSAVEIS, corpoResponsavel(novoCpf()), tokenCom(municipioA, List.of("inscricao:inscricao:add")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode json = objectMapper.readTree(resposta.getBody());
        assertThat(json.get("campos").get(0).asText()).isEqualTo("nome");
        assertThat(semPermissao.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("crianca: 201 so com criancaId para um responsavel do proprio municipio")
    void cadastrarCriancaDevolve201() throws Exception {
        long responsavelId = cadastrarResponsavel(municipioA);

        ResponseEntity<String> resposta = enviar(
                HttpMethod.POST, CAMINHO_CRIANCAS, corpoCrianca(responsavelId), tokenCom(municipioA, List.of("inscricao:crianca:add")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(resposta.getBody());
        assertThat(json.fieldNames()).toIterable().containsExactly("criancaId");
        assertThat(json.get("criancaId").asLong()).isPositive();
    }

    @Test
    @DisplayName("crianca: responsavel inexistente ou de outro municipio responde 404")
    void cadastrarCriancaComResponsavelInvisivelResponde404() {
        long responsavelDoA = cadastrarResponsavel(municipioA);
        String tokenB = tokenCom(municipioB, List.of("*"));

        ResponseEntity<String> deOutroMunicipio = enviar(HttpMethod.POST, CAMINHO_CRIANCAS, corpoCrianca(responsavelDoA), tokenB);
        ResponseEntity<String> inexistente = enviar(HttpMethod.POST, CAMINHO_CRIANCAS, corpoCrianca(999_999L), tokenB);

        assertThat(deOutroMunicipio.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inexistente.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("crianca: corpo invalido responde 400, sem token 401 e sem permissao 403")
    void cadastrarCriancaValidaCorpoETokenEPermissao() {
        long responsavelId = cadastrarResponsavel(municipioA);
        Map<String, Object> invalido = corpoCrianca(responsavelId);
        invalido.remove("nome");

        ResponseEntity<String> corpoInvalido = enviar(HttpMethod.POST, CAMINHO_CRIANCAS, invalido, tokenCom(municipioA, List.of("*")));
        ResponseEntity<String> semToken = enviar(HttpMethod.POST, CAMINHO_CRIANCAS, corpoCrianca(responsavelId), null);
        ResponseEntity<String> semPermissao = enviar(
                HttpMethod.POST, CAMINHO_CRIANCAS, corpoCrianca(responsavelId), tokenCom(municipioA, List.of("inscricao:fila:detail")));

        assertThat(corpoInvalido.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(semToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(semPermissao.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("crianca cadastrada pelo endpoint pode ser inscrita pelo criancaId e entra na fila do Redis")
    void criancaCadastradaPodeSerInscrita() throws Exception {
        long responsavelId = cadastrarResponsavel(municipioA);
        ResponseEntity<String> crianca = enviar(HttpMethod.POST, CAMINHO_CRIANCAS, corpoCrianca(responsavelId), tokenCom(municipioA, List.of("*")));
        long criancaId = objectMapper.readTree(crianca.getBody()).get("criancaId").asLong();
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("criancaId", criancaId);
        corpo.put("turmaId", turmaDoMunicipioA);
        corpo.put("rendaPerCapita", 500);
        corpo.put("criterioVulnerabilidade", false);

        ResponseEntity<String> inscricao = enviar(HttpMethod.POST, CAMINHO_INSCRICOES, corpo, tokenCom(municipioA, List.of("*")));

        assertThat(inscricao.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long inscricaoId = objectMapper.readTree(inscricao.getBody()).get("inscricaoId").asLong();
        assertThat(redisTemplate.opsForZSet().score(chave(municipioA, turmaDoMunicipioA), String.valueOf(inscricaoId)))
                .isEqualTo(1.0);
    }

    private long inscrever(Long turmaId) {
        ResponseEntity<String> resposta = enviar(
                HttpMethod.POST, CAMINHO_INSCRICOES, corpoInscricao(novoCpf(), turmaId), tokenCom(municipioA, List.of("*")));
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        try {
            return objectMapper.readTree(resposta.getBody()).get("inscricaoId").asLong();
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private long cadastrarResponsavel(Long municipioId) {
        ResponseEntity<String> resposta =
                enviar(HttpMethod.POST, CAMINHO_RESPONSAVEIS, corpoResponsavel(novoCpf()), tokenCom(municipioId, List.of("*")));
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        try {
            return objectMapper.readTree(resposta.getBody()).get("responsavelId").asLong();
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private long posicaoPelaApi(Long municipioId, long inscricaoId, Long turmaId) throws Exception {
        ResponseEntity<String> resposta = consultarPosicao(municipioId, inscricaoId, turmaId);
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resposta.getBody()).get("posicao").asLong();
    }

    private ResponseEntity<String> consultarPosicao(Long municipioId, long inscricaoId, Long turmaId) {
        return enviar(
                HttpMethod.GET,
                CAMINHO_INSCRICOES + "/" + inscricaoId + "/posicao?turmaId=" + turmaId,
                null,
                tokenCom(municipioId, List.of("inscricao:fila:detail")));
    }

    private ResponseEntity<String> enviar(HttpMethod metodo, String caminho, Object corpo, String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            String json = corpo == null ? null : objectMapper.writeValueAsString(corpo);
            return restTemplate.exchange(caminho, metodo, new HttpEntity<>(json, headers), String.class);
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private static String chave(Long municipioId, Long turmaId) {
        return "fila:" + municipioId + ":turma:" + turmaId;
    }

    private String tokenCom(Long municipioId, List<String> permissoes) {
        return stpLogic.createTokenValue(
                1L, "default", 3600, Map.of("municipioId", municipioId, "perms", permissoes));
    }

    private String novoCpf() {
        return String.format("%03d.000.000-00", sequenciaCpf.incrementAndGet());
    }

    private Map<String, Object> corpoResponsavel(String cpf) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("nome", "Maria Responsavel");
        corpo.put("cpf", cpf);
        corpo.put("telefone", "11999990000");
        corpo.put("email", "maria@example.com");
        corpo.put(
                "endereco",
                Map.of("rua", "Rua A", "numero", 10, "bairro", "Centro", "latitude", -23.55, "longitude", -46.63));
        return corpo;
    }

    private Map<String, Object> corpoCrianca(Long responsavelId) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("responsavelId", responsavelId);
        corpo.put("nome", "Joao Crianca");
        corpo.put("dataNascimento", "2024-03-10");
        return corpo;
    }

    private Map<String, Object> corpoInscricao(String cpf, Long turmaId) {
        Map<String, Object> crianca = new HashMap<>();
        crianca.put("nome", "Joao Crianca");
        crianca.put("dataNascimento", "2024-03-10");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("responsavel", corpoResponsavel(cpf));
        corpo.put("crianca", crianca);
        corpo.put("turmaId", turmaId);
        corpo.put("rendaPerCapita", 1234.56);
        corpo.put("criterioVulnerabilidade", true);
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

    private Long criarCreche(Long municipioId) {
        return emTenant(municipioId, () -> {
            Creche creche = new Creche();
            creche.setMunicipioId(municipioId);
            creche.setNome("Creche de Teste");
            creche.setCapacidadeTotal(50);
            creche.setEndereco(new Endereco("Rua B", 20, "Centro", "01000-000", "Sao Paulo", "SP", -23.56, -46.64));
            return crecheRepository.saveAndFlush(creche).getId();
        });
    }

    private Long criarTurma(Long municipioId, Long crecheId) {
        return emTenant(municipioId, () -> {
            Turma turma = new Turma();
            turma.setMunicipioId(municipioId);
            turma.setCreche(crecheRepository.getReferenceById(crecheId));
            turma.setFaixaEtaria(FaixaEtaria.BERCARIO);
            turma.setVagasTotais(5);
            turma.setVagasDisponiveis(5);
            return turmaRepository.saveAndFlush(turma).getId();
        });
    }
}
