package org.alocacreche.core.inscricao;

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
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.alocacreche.core.outbox.repository.OutboxEventRepository;
import org.assertj.core.data.Offset;
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
 * Prova ponta a ponta o unico {@code POST /api/v1/inscricao/inscricoes} com Postgres real: os dois
 * modos de identificar a crianca (criancaId ou cadastro inline), score de prioridade, posicao na
 * fila, evento na Outbox, erros 400, 404 e 409, 401, 403 e isolamento entre municipios (RLS).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("POST /api/v1/inscricao/inscricoes ponta a ponta")
class InscricaoControllerIT {

    private static final String CAMINHO = "/api/v1/inscricao/inscricoes";
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
    @Autowired private CrecheRepository crecheRepository;
    @MockitoSpyBean private TurmaRepository turmaRepository;
    @MockitoSpyBean private ResponsavelRepository responsavelRepository;
    @MockitoSpyBean private CriancaRepository criancaRepository;
    @MockitoSpyBean private InscricaoRepository inscricaoRepository;
    @MockitoSpyBean private FilaEsperaRepository filaEsperaRepository;
    @MockitoSpyBean private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
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
    void prepararTurmaELimparOutbox() {
        outboxEventRepository.deleteAll();
        turmaDoMunicipioA = criarTurma(municipioA, crecheDoMunicipioA);
    }

    @Test
    @DisplayName("token com * devolve 201 com inscricaoId e posicao 1, grava as entidades e o evento fila.atualizada")
    void inscricaoValidaDevolve201ECriaTudo() throws Exception {
        String cpf = novoCpf();

        ResponseEntity<String> resposta = postar(corpo(cpf, turmaDoMunicipioA), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode corpoResposta = objectMapper.readTree(resposta.getBody());
        long inscricaoId = corpoResposta.get("inscricaoId").asLong();
        assertThat(corpoResposta.get("posicao").asInt()).isEqualTo(1);

        emTenant(municipioA, () -> {
            Responsavel responsavel = responsavelRepository.findByCpf(cpf).orElseThrow();
            assertThat(responsavel.getNome()).isEqualTo("Maria Responsavel");
            assertThat(responsavel.getMunicipioId()).isEqualTo(municipioA);
            assertThat(criancaRepository.findByResponsavelId(responsavel.getId()))
                    .extracting(Crianca::getNome)
                    .containsExactly("Joao Crianca");
            Inscricao inscricao = inscricaoRepository.findById(inscricaoId).orElseThrow();
            assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.AGUARDANDO);
            assertThat(inscricao.getCriterioVulnerabilidade()).isTrue();
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaDoMunicipioA))
                    .extracting(FilaEspera::getPosicao)
                    .containsExactly(1);
            return null;
        });

        List<OutboxEvent> eventos = outboxEventRepository.findAll();
        assertThat(eventos).hasSize(1);
        OutboxEvent evento = eventos.get(0);
        assertThat(evento.getTipoEvento()).isEqualTo("fila.atualizada");
        assertThat(evento.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDENTE);
        JsonNode json = objectMapper.readTree(evento.getPayload());
        assertThat(nomesDosCampos(json))
                .containsExactlyInAnyOrder(
                        "tipoEvento",
                        "municipioId",
                        "turmaId",
                        "crecheId",
                        "inscricaoId",
                        "dataEntrada",
                        "dataSaida",
                        "ocorridoEm");
        assertThat(json.get("tipoEvento").asText()).isEqualTo("fila.atualizada");
        assertThat(json.get("municipioId").asLong()).isEqualTo(municipioA);
        assertThat(json.get("turmaId").asLong()).isEqualTo(turmaDoMunicipioA);
        assertThat(json.get("crecheId").asLong()).isEqualTo(crecheDoMunicipioA);
        assertThat(json.get("inscricaoId").asLong()).isEqualTo(inscricaoId);
        assertThat(json.get("dataEntrada").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(json.get("dataSaida").isNull()).isTrue();
        assertThat(json.get("ocorridoEm").isTextual()).isTrue();
    }

    @Test
    @DisplayName("duas inscricoes na mesma turma recebem as posicoes 1 e 2 e duas linhas de evento")
    void duasInscricoesNaMesmaTurmaDaoPosicoesUmEDois() throws Exception {
        String token = tokenCom(municipioA, List.of(PERMISSAO));

        ResponseEntity<String> primeira = postar(corpo(novoCpf(), turmaDoMunicipioA), token);
        ResponseEntity<String> segunda = postar(corpo(novoCpf(), turmaDoMunicipioA), token);

        assertThat(objectMapper.readTree(primeira.getBody()).get("posicao").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(segunda.getBody()).get("posicao").asInt()).isEqualTo(2);
        assertThat(outboxEventRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("falha ao gravar o evento desfaz a inscricao: Responsavel, Inscricao e FilaEspera nao persistem")
    void falhaNoEventoDesfazAInscricao() {
        String cpf = novoCpf();
        long inscricoesAntes = emTenant(municipioA, inscricaoRepository::count);
        doThrow(new IllegalStateException("falha ao gravar fila.atualizada"))
                .when(outboxEventRepository)
                .save(any(OutboxEvent.class));

        ResponseEntity<String> resposta =
                postar(corpo(cpf, turmaDoMunicipioA), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode().is5xxServerError()).isTrue();
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(responsavelRepository.findByCpf(cpf)).isEmpty();
            assertThat(inscricaoRepository.count()).isEqualTo(inscricoesAntes);
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaDoMunicipioA)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("CPF repetido responde 409 sem criar segundo registro nem segundo evento")
    void cpfRepetidoResponde409() {
        String cpf = novoCpf();
        String token = tokenCom(municipioA, List.of("*"));
        postar(corpo(cpf, turmaDoMunicipioA), token);

        ResponseEntity<String> resposta = postar(corpo(cpf, turmaDoMunicipioA), token);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEventRepository.findAll()).hasSize(1);
        emTenant(municipioA, () -> {
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaDoMunicipioA)).hasSize(1);
            return null;
        });
    }

    @Test
    @DisplayName("CPF que ja existe em outro municipio tambem responde 409 sem evento")
    void cpfDeOutroMunicipioResponde409() {
        String cpf = novoCpf();
        Long turmaDoB = criarTurma(municipioB, criarCreche(municipioB));
        postar(corpo(cpf, turmaDoB), tokenCom(municipioB, List.of("*")));
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta =
                postar(corpo(cpf, turmaDoMunicipioA), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("turma inexistente responde 404 sem criar registro nem evento")
    void turmaInexistenteResponde404() {
        String cpf = novoCpf();

        ResponseEntity<String> resposta = postar(corpo(cpf, 999_999L), tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(responsavelRepository.findByCpf(cpf)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("campos invalidos respondem 400 com a lista de campos e sem escrita")
    void camposInvalidosRespondem400ComALista() throws Exception {
        String cpf = novoCpf();
        Map<String, Object> corpo = corpo(cpf, turmaDoMunicipioA);
        corpo.remove("turmaId");
        ((Map<String, Object>) corpo.get("responsavel")).put("nome", " ");
        ((Map<String, Object>) corpo.get("crianca")).put("dataNascimento", LocalDate.now().plusDays(1).toString());
        clearInvocations(
                responsavelRepository,
                criancaRepository,
                inscricaoRepository,
                filaEsperaRepository,
                turmaRepository);

        ResponseEntity<String> resposta = postar(corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(
                responsavelRepository,
                criancaRepository,
                inscricaoRepository,
                filaEsperaRepository,
                turmaRepository);
        JsonNode json = objectMapper.readTree(resposta.getBody());
        List<String> campos = new ArrayList<>();
        json.get("campos").forEach(campo -> campos.add(campo.asText()));
        assertThat(campos)
                .containsExactlyInAnyOrder("turmaId", "responsavel.nome", "crianca.dataNascimento");
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(responsavelRepository.findByCpf(cpf)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("JSON ilegivel responde 400")
    void jsonIlegivelResponde400() {
        HttpHeaders headers = cabecalhos(tokenCom(municipioA, List.of("*")));

        ResponseEntity<String> resposta =
                restTemplate.exchange(
                        CAMINHO, HttpMethod.POST, new HttpEntity<>("{isto nao e json", headers), String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("JSON ilegivel devolve o corpo padrao do tratador de requisicao unico")
    void jsonIlegivelDevolveCorpoPadrao() throws Exception {
        HttpHeaders headers = cabecalhos(tokenCom(municipioA, List.of("*")));

        ResponseEntity<String> resposta =
                restTemplate.exchange(
                        CAMINHO, HttpMethod.POST, new HttpEntity<>("{isto nao e json", headers), String.class);

        JsonNode corpo = objectMapper.readTree(resposta.getBody());
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(corpo.get("erro").asText()).isEqualTo("Requisição inválida");
        assertThat(corpo.has("campos")).isFalse();
    }

    @Test
    @DisplayName("sem token responde 401 e nada e gravado")
    void semTokenResponde401() {
        ResponseEntity<String> resposta = postar(corpo(novoCpf(), turmaDoMunicipioA), null);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("token sem a permissao inscricao:inscricao:add responde 403 e nada e gravado")
    void tokenSemPermissaoResponde403() {
        ResponseEntity<String> resposta =
                postar(
                        corpo(novoCpf(), turmaDoMunicipioA),
                        tokenCom(municipioA, List.of("inscricao:fila:list")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("token sem a claim municipioId responde 403")
    void tokenSemMunicipioResponde403() {
        String token = stpLogic.createTokenValue(1L, "default", 3600, Map.of("perms", List.of("*")));

        ResponseEntity<String> resposta = postar(corpo(novoCpf(), turmaDoMunicipioA), token);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("inscricao no municipio 2 nao enxerga a turma do municipio 1 e responde 404")
    void municipioBNaoEnxergaTurmaDoMunicipioA() {
        ResponseEntity<String> resposta =
                postar(corpo(novoCpf(), turmaDoMunicipioA), tokenCom(municipioB, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("com criancaId devolve 201 com score 0,7, posicao 1 e persiste renda, vulnerabilidade e score")
    void inscricaoPorCriancaIdDevolve201ComScore() throws Exception {
        Long criancaId = criarCrianca(municipioA);

        ResponseEntity<String> resposta =
                postar(
                        corpoPorCrianca(criancaId, turmaDoMunicipioA, "400.00", true),
                        tokenCom(municipioA, List.of(PERMISSAO)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(resposta.getBody());
        long inscricaoId = json.get("inscricaoId").asLong();
        assertThat(json.get("posicao").asInt()).isEqualTo(1);
        assertThat(json.get("scorePrioridade").asDouble()).isCloseTo(0.7, Offset.offset(1e-9));
        assertThat(json.get("status").asText()).isEqualTo("AGUARDANDO");
        assertThat(json.has("municipio")).isFalse();
        assertThat(json.has("crianca")).isFalse();
        emTenant(municipioA, () -> {
            Inscricao salva = inscricaoRepository.findById(inscricaoId).orElseThrow();
            assertThat(salva.getScorePrioridade()).isCloseTo(0.7, Offset.offset(1e-9));
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
                postar(
                        corpoPorCrianca(criancaId, turmaDoMunicipioA, "1200.00", false),
                        tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(resposta.getBody()).get("scorePrioridade").asDouble())
                .isCloseTo(0.2, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("municipioId do corpo diferente do do token responde 403 e nada e gravado")
    void municipioDoCorpoDiferenteDoTokenResponde403() {
        Long criancaId = criarCrianca(municipioA);
        Map<String, Object> corpo = corpoPorCrianca(criancaId, turmaDoMunicipioA, "400.00", true);
        corpo.put("municipioId", municipioB);

        ResponseEntity<String> resposta = postar(corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("crianca inexistente responde 404 sem evento")
    void criancaInexistenteResponde404() {
        ResponseEntity<String> resposta =
                postar(
                        corpoPorCrianca(999_999L, turmaDoMunicipioA, "400.00", true),
                        tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("crianca de outro municipio e invisivel pela RLS e responde 404")
    void criancaDeOutroMunicipioResponde404() {
        Long criancaDoB = criarCrianca(municipioB);

        ResponseEntity<String> resposta =
                postar(
                        corpoPorCrianca(criancaDoB, turmaDoMunicipioA, "400.00", true),
                        tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("sem criancaId e sem cadastro inline responde 400 com corpo erro")
    void semNenhumModoResponde400() throws Exception {
        Map<String, Object> corpo = corpoPorCrianca(1L, turmaDoMunicipioA, "400.00", true);
        corpo.remove("criancaId");

        ResponseEntity<String> resposta = postar(corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(resposta.getBody()).get("erro").asText()).isNotBlank();
    }

    @Test
    @DisplayName("criancaId junto com cadastro inline responde 400 e nada e gravado")
    void osDoisModosResponde400() throws Exception {
        String cpf = novoCpf();
        Map<String, Object> corpo = corpo(cpf, turmaDoMunicipioA);
        corpo.put("criancaId", criarCrianca(municipioA));

        ResponseEntity<String> resposta = postar(corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(resposta.getBody()).get("erro").asText()).isNotBlank();
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(responsavelRepository.findByCpf(cpf)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("turmaId ausente responde 400 apontando o campo")
    void turmaIdAusenteResponde400() throws Exception {
        Map<String, Object> corpo = corpoPorCrianca(criarCrianca(municipioA), turmaDoMunicipioA, "400.00", true);
        corpo.remove("turmaId");

        ResponseEntity<String> resposta = postar(corpo, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(resposta.getBody()).get("campos").toString()).contains("turmaId");
    }

    private ResponseEntity<String> postar(Map<String, Object> corpo, String token) {
        try {
            return restTemplate.exchange(
                    CAMINHO,
                    HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(corpo), cabecalhos(token)),
                    String.class);
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private HttpHeaders cabecalhos(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return headers;
    }

    private String tokenCom(Long municipioId, List<String> permissoes) {
        return stpLogic.createTokenValue(
                1L, "default", 3600, Map.of("municipioId", municipioId, "perms", permissoes));
    }

    private String novoCpf() {
        return String.format("%03d.000.000-00", sequenciaCpf.incrementAndGet());
    }

    private Map<String, Object> corpo(String cpf, Long turmaId) {
        Map<String, Object> responsavel = new HashMap<>();
        responsavel.put("nome", "Maria Responsavel");
        responsavel.put("cpf", cpf);
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
        corpo.put("rendaPerCapita", 1234.56);
        corpo.put("criterioVulnerabilidade", true);
        return corpo;
    }

    private Map<String, Object> corpoPorCrianca(
            Long criancaId, Long turmaId, String renda, boolean vulnerabilidade) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("criancaId", criancaId);
        corpo.put("turmaId", turmaId);
        corpo.put("rendaPerCapita", renda);
        corpo.put("criterioVulnerabilidade", vulnerabilidade);
        return corpo;
    }

    private Long criarCrianca(Long municipioId) {
        return emTenant(municipioId, () -> {
            Responsavel responsavel = new Responsavel();
            responsavel.setMunicipioId(municipioId);
            responsavel.setNome("Responsavel de Teste");
            responsavel.setCpf(novoCpf());
            responsavel.setEndereco(new Endereco("Rua A", 10, "Centro", "01000-000", "Sao Paulo", "SP", -23.55, -46.63));
            Crianca crianca = new Crianca();
            crianca.setMunicipioId(municipioId);
            crianca.setNome("Crianca de Teste");
            crianca.setDataNascimento(LocalDate.of(2024, 3, 10));
            responsavel.adicionarCrianca(crianca);
            return responsavelRepository.saveAndFlush(responsavel).getCriancas().get(0).getId();
        });
    }

    private static List<String> nomesDosCampos(JsonNode json) {
        List<String> nomes = new ArrayList<>();
        json.fieldNames().forEachRemaining(nomes::add);
        return nomes;
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
