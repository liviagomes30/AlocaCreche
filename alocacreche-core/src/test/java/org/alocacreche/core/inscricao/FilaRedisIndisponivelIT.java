package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Redis fora do ar durante a alocacao (container pausado, com Postgres real): a alocacao segue
 * (fail-open) e, como o snapshot novo nao chegou ao Redis, a posicao em cache fica velha ate o TTL
 * das chaves. Depois que a chave expira e o Redis volta, a API deixa de devolver a posicao velha e
 * responde a do banco. Usa TTL curto (4 s) e containers proprios porque pausa o Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Fila de espera com o Redis fora do ar")
class FilaRedisIndisponivelIT {

    private static final String CAMINHO_INSCRICOES = "/api/v1/inscricao/inscricoes";
    private static final String CAMINHO_ALOCAR = "/api/v1/matricula/vagas/alocar";
    private static final long TTL_SEGUNDOS = 4;

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
        registry.add("alocacreche.fila.redis.ttl", () -> TTL_SEGUNDOS + "s");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StpLogicJwtForStateless stpLogic;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private MunicipioRepository municipioRepository;
    @Autowired private CrecheRepository crecheRepository;
    @Autowired private TurmaRepository turmaRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("alocar com o Redis pausado responde 201 e, expirado o TTL, a posicao volta correta do banco")
    void redisForaNaAlocacaoNaoDeixaPosicaoVelhaDepoisDoTtl() throws Exception {
        Long municipioId = criarMunicipio();
        Long turmaId = criarTurma(municipioId);
        long primeira = inscrever(municipioId, turmaId, 500);
        long segunda = inscrever(municipioId, turmaId, 1000);
        long terceira = inscrever(municipioId, turmaId, 1500);
        String chave = "fila:" + municipioId + ":turma:" + turmaId;
        assertThat(posicaoPelaApi(municipioId, segunda, turmaId)).isEqualTo(2);
        assertThat(redisTemplate.getExpire(chave)).as("TTL aplicado na chave da fila").isBetween(1L, TTL_SEGUNDOS);

        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        ResponseEntity<String> alocacao;
        try {
            alocacao = enviar(HttpMethod.POST, CAMINHO_ALOCAR, Map.of("turmaId", turmaId), tokenCom(municipioId, List.of("*")));
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }

        assertThat(alocacao.getStatusCode()).as("fail-open: o Redis fora nao derruba a alocacao").isEqualTo(HttpStatus.CREATED);
        Thread.sleep((TTL_SEGUNDOS + 2) * 1000);

        assertThat(redisTemplate.hasKey(chave)).as("a chave velha expirou").isFalse();
        assertThat(posicaoPelaApi(municipioId, segunda, turmaId)).as("posicao real no banco, nao a velha").isEqualTo(1);
        assertThat(posicaoPelaApi(municipioId, terceira, turmaId)).isEqualTo(2);
        assertThat(consultarPosicao(municipioId, primeira, turmaId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private long inscrever(Long municipioId, Long turmaId, int renda) throws Exception {
        Map<String, Object> corpo = corpoInscricao(turmaId, renda);
        ResponseEntity<String> resposta =
                enviar(HttpMethod.POST, CAMINHO_INSCRICOES, corpo, tokenCom(municipioId, List.of("*")));
        assertThat(resposta.getStatusCode()).as(resposta.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(resposta.getBody()).get("inscricaoId").asLong();
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
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            String json = corpo == null ? null : objectMapper.writeValueAsString(corpo);
            return restTemplate.exchange(caminho, metodo, new HttpEntity<>(json, headers), String.class);
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private String tokenCom(Long municipioId, List<String> permissoes) {
        return stpLogic.createTokenValue(1L, "default", 3600, Map.of("municipioId", municipioId, "perms", permissoes));
    }

    private int sequenciaCpf = 100;

    private Map<String, Object> corpoInscricao(Long turmaId, int renda) {
        Map<String, Object> responsavel = new HashMap<>();
        responsavel.put("nome", "Maria Responsavel");
        responsavel.put("cpf", String.format("%03d.000.000-00", ++sequenciaCpf));
        responsavel.put("telefone", "11999990000");
        responsavel.put("email", "maria@example.com");
        responsavel.put(
                "endereco",
                Map.of("rua", "Rua A", "numero", 10, "bairro", "Centro", "latitude", -23.55, "longitude", -46.63));
        Map<String, Object> crianca = new HashMap<>();
        crianca.put("nome", "Joao Crianca");
        crianca.put("dataNascimento", "2024-03-10");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("responsavel", responsavel);
        corpo.put("crianca", crianca);
        corpo.put("turmaId", turmaId);
        corpo.put("rendaPerCapita", renda);
        corpo.put("criterioVulnerabilidade", false);
        return corpo;
    }

    private <T> T emTenant(Long municipioId, Supplier<T> acao) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> {
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

    private Long criarTurma(Long municipioId) {
        return emTenant(municipioId, () -> {
            Creche creche = new Creche();
            creche.setMunicipioId(municipioId);
            creche.setNome("Creche de Teste");
            creche.setCapacidadeTotal(50);
            creche.setEndereco(new Endereco("Rua B", 20, "Centro", "01000-000", "Sao Paulo", "SP", -23.56, -46.64));
            Creche salva = crecheRepository.saveAndFlush(creche);
            Turma turma = new Turma();
            turma.setMunicipioId(municipioId);
            turma.setCreche(salva);
            turma.setFaixaEtaria(FaixaEtaria.BERCARIO);
            turma.setVagasTotais(5);
            turma.setVagasDisponiveis(5);
            return turmaRepository.saveAndFlush(turma).getId();
        });
    }
}
