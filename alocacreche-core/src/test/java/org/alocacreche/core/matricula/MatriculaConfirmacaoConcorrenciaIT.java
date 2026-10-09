package org.alocacreche.core.matricula;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prova com Postgres real e duas requisicoes HTTP simultaneas que a confirmacao da mesma matricula
 * e serializada: exatamente uma recebe 200, a outra 409, e a Outbox recebe um unico
 * matricula.atualizada CONFIRMADA para a matricula (REV-06, REV-07). O publicador agendado fica
 * desligado por padrao nos testes, entao as linhas da Outbox nao sao consumidas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("POST /api/v1/matricula/matriculas/{id}/confirmar com duas confirmacoes simultaneas")
class MatriculaConfirmacaoConcorrenciaIT {

    private static final String CAMINHO_CRECHES = "/api/v1/matricula/creches";
    private static final String CAMINHO_TURMAS = "/api/v1/matricula/turmas";
    private static final String CAMINHO_INSCRICOES = "/api/v1/inscricao/inscricoes";
    private static final String CAMINHO_ALOCAR = "/api/v1/matricula/vagas/alocar";

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
    @Autowired private OutboxEventRepository outboxEventRepository;

    private final AtomicInteger sequenciaCpf = new AtomicInteger(500);

    private Long municipioId;
    private String token;

    @BeforeAll
    void prepararMunicipio() {
        municipioId = criarMunicipio();
        token = tokenCom(municipioId);
    }

    @RepeatedTest(5)
    @DisplayName("duas confirmacoes simultaneas da mesma matricula: um 200, um 409 e um unico evento CONFIRMADA")
    void duasConfirmacoesSimultaneasConfirmamUmaVez() throws Exception {
        Long matriculaId = alocarNovaMatricula();

        List<Integer> respostas = confirmarSimultaneamente(matriculaId);

        assertThat(respostas)
                .containsExactlyInAnyOrder(HttpStatus.OK.value(), HttpStatus.CONFLICT.value());
        assertThat(eventosConfirmadosDa(matriculaId)).hasSize(1);
    }

    private List<Integer> confirmarSimultaneamente(Long matriculaId) throws Exception {
        CyclicBarrier largada = new CyclicBarrier(2);
        Callable<Integer> confirmacao =
                () -> {
                    largada.await(10, TimeUnit.SECONDS);
                    return confirmar(matriculaId).getStatusCode().value();
                };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futuros =
                    List.of(executor.submit(confirmacao), executor.submit(confirmacao));
            List<Integer> respostas = new ArrayList<>();
            for (Future<Integer> futuro : futuros) {
                respostas.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return respostas;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<OutboxEvent> eventosConfirmadosDa(Long matriculaId) throws Exception {
        List<OutboxEvent> encontrados = new ArrayList<>();
        for (OutboxEvent evento : outboxEventRepository.findAll()) {
            if (!"matricula.atualizada".equals(evento.getTipoEvento())) {
                continue;
            }
            JsonNode json = objectMapper.readTree(evento.getPayload());
            if (json.get("matriculaId").asLong() == matriculaId
                    && "CONFIRMADA".equals(json.get("status").asText())) {
                encontrados.add(evento);
            }
        }
        return encontrados;
    }

    private Long alocarNovaMatricula() {
        Long crecheId = criarCreche();
        Long turmaId = criarTurma(crecheId);
        inscrever(turmaId);
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("turmaId", turmaId);
        return idDe(postar(CAMINHO_ALOCAR, corpo), "matriculaId");
    }

    private Long criarCreche() {
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
        return idDe(postar(CAMINHO_CRECHES, corpo), "crecheId");
    }

    private Long criarTurma(Long crecheId) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("crecheId", crecheId);
        corpo.put("faixaEtaria", "MATERNAL");
        corpo.put("vagasTotais", 3);
        return idDe(postar(CAMINHO_TURMAS, corpo), "turmaId");
    }

    private void inscrever(Long turmaId) {
        Map<String, Object> endereco = new HashMap<>();
        endereco.put("rua", "Rua A");
        endereco.put("numero", 10);
        endereco.put("bairro", "Centro");
        endereco.put("latitude", -23.55);
        endereco.put("longitude", -46.63);
        Map<String, Object> responsavel = new HashMap<>();
        responsavel.put("nome", "Maria Responsavel");
        responsavel.put("cpf", String.format("%03d.000.000-00", sequenciaCpf.incrementAndGet()));
        responsavel.put("endereco", endereco);
        Map<String, Object> crianca = new HashMap<>();
        crianca.put("nome", "Joao Crianca");
        crianca.put("dataNascimento", "2024-03-10");
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("responsavel", responsavel);
        corpo.put("crianca", crianca);
        corpo.put("turmaId", turmaId);
        corpo.put("rendaPerCapita", 1234.56);
        corpo.put("criterioVulnerabilidade", true);
        assertThat(postar(CAMINHO_INSCRICOES, corpo).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> confirmar(Long matriculaId) {
        return restTemplate.exchange(
                "/api/v1/matricula/matriculas/" + matriculaId + "/confirmar",
                HttpMethod.POST,
                new HttpEntity<>(cabecalhos()),
                String.class);
    }

    private ResponseEntity<String> postar(String caminho, Map<String, Object> corpo) {
        try {
            return restTemplate.exchange(
                    caminho,
                    HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(corpo), cabecalhos()),
                    String.class);
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private Long idDe(ResponseEntity<String> resposta, String campo) {
        try {
            return objectMapper.readTree(resposta.getBody()).get(campo).asLong();
        } catch (Exception excecao) {
            throw new IllegalStateException("Resposta inesperada: " + resposta.getStatusCode(), excecao);
        }
    }

    private HttpHeaders cabecalhos() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return headers;
    }

    private String tokenCom(Long municipio) {
        return stpLogic.createTokenValue(
                1L, "default", 3600, Map.of("municipioId", municipio, "perms", List.of("*")));
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
