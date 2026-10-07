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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.inscricao.FilaEspera;
import org.alocacreche.core.inscricao.FilaEsperaRepository;
import org.alocacreche.core.inscricao.InscricaoRepository;
import org.alocacreche.core.inscricao.StatusInscricao;
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
 * Prova ponta a ponta {@code POST /api/v1/matricula/vagas/alocar} e {@code
 * /matriculas/{id}/confirmar} com Postgres real (PROD-11, PROD-14, PROD-16, PROD-17, PROD-18): os
 * eventos na Outbox, as recusas 404, 409 e 422, 401, 403 e o isolamento entre municipios.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("POST /api/v1/matricula/vagas/alocar e /matriculas/{id}/confirmar ponta a ponta")
class MatriculaControllerIT {

    private static final String CAMINHO_CRECHES = "/api/v1/matricula/creches";
    private static final String CAMINHO_TURMAS = "/api/v1/matricula/turmas";
    private static final String CAMINHO_INSCRICOES = "/api/v1/inscricao/inscricoes";
    private static final String CAMINHO_ALOCAR = "/api/v1/matricula/vagas/alocar";
    private static final String PERMISSAO_ALOCAR = "matricula:vaga:alocar";
    private static final String PERMISSAO_CONFIRMAR = "matricula:matricula:confirmar";

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
    @MockitoSpyBean private TurmaRepository turmaRepository;
    @MockitoSpyBean private MatriculaRepository matriculaRepository;
    @MockitoSpyBean private InscricaoRepository inscricaoRepository;
    @MockitoSpyBean private FilaEsperaRepository filaEsperaRepository;
    @MockitoSpyBean private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final AtomicInteger sequenciaCpf = new AtomicInteger(100);

    private Long municipioA;
    private Long municipioB;
    private Long crecheDoMunicipioA;

    @BeforeAll
    void prepararMunicipios() {
        municipioA = criarMunicipio();
        municipioB = criarMunicipio();
    }

    @BeforeEach
    void prepararCreche() {
        crecheDoMunicipioA = criarCrechePorHttp(municipioA);
    }

    @Test
    @DisplayName("alocar devolve 201 com matriculaId e grava as tres linhas de evento com os campos do contrato")
    void alocarDevolve201EGravaOsTresEventos() throws Exception {
        Long turmaId = criarTurmaPorHttp(municipioA, 3);
        Long inscricaoId = inscreverPorHttp(municipioA, turmaId, true);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta = alocar(turmaId, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long matriculaId = objectMapper.readTree(resposta.getBody()).get("matriculaId").asLong();
        emTenant(municipioA, () -> {
            Matricula matricula = matriculaRepository.findById(matriculaId).orElseThrow();
            assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.PENDENTE);
            assertThat(matricula.getInscricaoId()).isEqualTo(inscricaoId);
            assertThat(matricula.getDistanciaKm()).isPositive();
            assertThat(matricula.getPrazoConfirmacao())
                    .isEqualTo(matricula.getDataConvocacao().plusDays(5));
            assertThat(turmaRepository.findById(turmaId).orElseThrow().getVagasDisponiveis()).isEqualTo(2);
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId)).isEmpty();
            assertThat(inscricaoRepository.findById(inscricaoId).orElseThrow().getStatus())
                    .isEqualTo(StatusInscricao.CONVOCADA);
            return null;
        });

        List<OutboxEvent> eventos = outboxEventRepository.findAll();
        assertThat(eventos).extracting(OutboxEvent::getTipoEvento)
                .containsExactlyInAnyOrder("vaga.liberada", "fila.atualizada", "matricula.atualizada");

        JsonNode vaga = payloadDe(eventos, "vaga.liberada");
        assertThat(nomesDosCampos(vaga))
                .containsExactlyInAnyOrder(
                        "tipoEvento", "municipioId", "turmaId", "crecheId", "vagasDisponiveis", "ocorridoEm");
        assertThat(vaga.get("municipioId").asLong()).isEqualTo(municipioA);
        assertThat(vaga.get("turmaId").asLong()).isEqualTo(turmaId);
        assertThat(vaga.get("crecheId").asLong()).isEqualTo(crecheDoMunicipioA);
        assertThat(vaga.get("vagasDisponiveis").asInt()).isEqualTo(2);

        JsonNode fila = payloadDe(eventos, "fila.atualizada");
        assertThat(nomesDosCampos(fila))
                .containsExactlyInAnyOrder(
                        "tipoEvento",
                        "municipioId",
                        "turmaId",
                        "crecheId",
                        "inscricaoId",
                        "dataEntrada",
                        "dataSaida",
                        "ocorridoEm");
        assertThat(fila.get("inscricaoId").asLong()).isEqualTo(inscricaoId);
        assertThat(fila.get("dataEntrada").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(fila.get("dataSaida").asText()).isEqualTo(LocalDate.now().toString());

        JsonNode matriculaEvento = payloadDe(eventos, "matricula.atualizada");
        assertThat(nomesDosCampos(matriculaEvento))
                .containsExactlyInAnyOrder(
                        "tipoEvento",
                        "municipioId",
                        "turmaId",
                        "crecheId",
                        "inscricaoId",
                        "matriculaId",
                        "status",
                        "distanciaKm",
                        "dataConvocacao",
                        "dataConfirmacao",
                        "ocorridoEm");
        assertThat(matriculaEvento.get("matriculaId").asLong()).isEqualTo(matriculaId);
        assertThat(matriculaEvento.get("status").asText()).isEqualTo("PENDENTE");
        assertThat(matriculaEvento.get("distanciaKm").asDouble()).isPositive();
        assertThat(matriculaEvento.get("dataConvocacao").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(matriculaEvento.get("dataConfirmacao").isNull()).isTrue();
    }

    @Test
    @DisplayName("com dois inscritos, a matricula vai para a inscricao de menor posicao e o segundo segue na fila")
    void alocarMatriculaAInscricaoDeMenorPosicao() {
        Long turmaId = criarTurmaPorHttp(municipioA, 1);
        inscreverPorHttp(municipioA, turmaId, true);
        inscreverPorHttp(municipioA, turmaId, true);
        List<Long> inscricoesNaFila =
                emTenant(municipioA, () -> {
                    List<FilaEspera> fila = filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId);
                    assertThat(fila).extracting(FilaEspera::getPosicao).containsExactly(1, 2);
                    return fila.stream().map(item -> item.getInscricao().getId()).toList();
                });
        Long inscricaoDaPosicaoUm = inscricoesNaFila.get(0);
        Long inscricaoDaPosicaoDois = inscricoesNaFila.get(1);

        ResponseEntity<String> resposta = alocar(turmaId, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        emTenant(municipioA, () -> {
            List<Matricula> matriculas = matriculaRepository.findByTurmaId(turmaId);
            assertThat(matriculas).hasSize(1);
            assertThat(matriculas.get(0).getInscricaoId()).isEqualTo(inscricaoDaPosicaoUm);
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId))
                    .extracting(item -> item.getInscricao().getId())
                    .containsExactly(inscricaoDaPosicaoDois);
            return null;
        });
    }

    @Test
    @DisplayName("alocar sem turmaId responde 400 sem consultar turma, fila nem matricula")
    void alocarSemTurmaIdNaoConsultaOBanco() {
        String token = tokenCom(municipioA, List.of("*"));
        clearInvocations(turmaRepository, filaEsperaRepository, matriculaRepository);

        ResponseEntity<String> resposta = postar(CAMINHO_ALOCAR, new HashMap<>(), token);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(turmaRepository, filaEsperaRepository, matriculaRepository);
    }

    @Test
    @DisplayName("alocar com JSON ilegivel responde 400 sem consultar turma, fila nem matricula")
    void alocarComJsonIlegivelNaoConsultaOBanco() {
        String token = tokenCom(municipioA, List.of("*"));
        clearInvocations(turmaRepository, filaEsperaRepository, matriculaRepository);

        ResponseEntity<String> resposta =
                restTemplate.exchange(
                        CAMINHO_ALOCAR,
                        HttpMethod.POST,
                        new HttpEntity<>("{isto nao e json", cabecalhos(token)),
                        String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(turmaRepository, filaEsperaRepository, matriculaRepository);
    }

    @Test
    @DisplayName("JSON ilegivel devolve o corpo padrao do tratador de requisicao unico")
    void jsonIlegivelDevolveCorpoPadrao() throws Exception {
        String token = tokenCom(municipioA, List.of("*"));

        ResponseEntity<String> resposta =
                restTemplate.exchange(
                        CAMINHO_ALOCAR,
                        HttpMethod.POST,
                        new HttpEntity<>("{isto nao e json", cabecalhos(token)),
                        String.class);

        JsonNode corpo = objectMapper.readTree(resposta.getBody());
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(corpo.get("erro").asText()).isEqualTo("Requisição inválida");
        assertThat(corpo.has("campos")).isFalse();
    }

    @Test
    @DisplayName("confirmar com id nao numerico responde 400 sem consultar matricula nem inscricao")
    void confirmarComIdNaoNumericoResponde400() {
        String token = tokenCom(municipioA, List.of("*"));
        clearInvocations(matriculaRepository, inscricaoRepository);

        ResponseEntity<String> resposta =
                restTemplate.exchange(
                        "/api/v1/matricula/matriculas/abc/confirmar",
                        HttpMethod.POST,
                        new HttpEntity<>(cabecalhos(token)),
                        String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(matriculaRepository, inscricaoRepository);
    }

    @Test
    @DisplayName("confirmar devolve 200, marca CONFIRMADA e grava matricula.atualizada com dataConfirmacao")
    void confirmarDevolve200EGravaEvento() throws Exception {
        Long matriculaId = alocarNovaMatricula(municipioA);
        Double distancia = emTenant(municipioA, () -> matriculaRepository.findById(matriculaId).orElseThrow().getDistanciaKm());
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta = confirmar(matriculaId, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        emTenant(municipioA, () -> {
            Matricula matricula = matriculaRepository.findById(matriculaId).orElseThrow();
            assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.CONFIRMADA);
            assertThat(matricula.getDataConfirmacao()).isNotNull();
            assertThat(inscricaoRepository.findById(matricula.getInscricaoId()).orElseThrow().getStatus())
                    .isEqualTo(StatusInscricao.MATRICULADA);
            return null;
        });
        List<OutboxEvent> eventos = outboxEventRepository.findAll();
        assertThat(eventos).hasSize(1);
        assertThat(eventos.get(0).getTipoEvento()).isEqualTo("matricula.atualizada");
        JsonNode json = objectMapper.readTree(eventos.get(0).getPayload());
        assertThat(json.get("status").asText()).isEqualTo("CONFIRMADA");
        assertThat(json.get("matriculaId").asLong()).isEqualTo(matriculaId);
        assertThat(json.get("distanciaKm").asDouble()).isEqualTo(distancia);
        assertThat(json.get("dataConfirmacao").asText()).isEqualTo(LocalDate.now().toString());
    }

    @Test
    @DisplayName("falha ao gravar o evento de confirmacao desfaz tudo: segue PENDENTE, inscricao CONVOCADA e sem linha na Outbox")
    void falhaNoEventoDeConfirmacaoDesfazAConfirmacao() {
        Long matriculaId = alocarNovaMatricula(municipioA);
        outboxEventRepository.deleteAll();
        doThrow(new IllegalStateException("falha ao gravar matricula.atualizada"))
                .when(outboxEventRepository)
                .save(any(OutboxEvent.class));

        ResponseEntity<String> resposta = confirmar(matriculaId, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode().is5xxServerError()).isTrue();
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            Matricula matricula = matriculaRepository.findById(matriculaId).orElseThrow();
            assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.PENDENTE);
            assertThat(matricula.getDataConfirmacao()).isNull();
            assertThat(inscricaoRepository.findById(matricula.getInscricaoId()).orElseThrow().getStatus())
                    .isEqualTo(StatusInscricao.CONVOCADA);
            return null;
        });
    }

    @Test
    @DisplayName("confirmar a mesma matricula duas vezes responde 409 na segunda, sem novo evento")
    void confirmarDuasVezesResponde409() {
        Long matriculaId = alocarNovaMatricula(municipioA);
        String token = tokenCom(municipioA, List.of("*"));
        confirmar(matriculaId, token);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> segunda = confirmar(matriculaId, token);

        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("confirmar depois do prazo responde 409, sem alterar estado nem gravar evento")
    void confirmarComPrazoVencidoResponde409() {
        Long matriculaId = alocarNovaMatricula(municipioA);
        emTenant(municipioA, () -> {
            Matricula matricula = matriculaRepository.findById(matriculaId).orElseThrow();
            matricula.setPrazoConfirmacao(LocalDateTime.now().minusMinutes(1));
            matriculaRepository.saveAndFlush(matricula);
            return null;
        });
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta = confirmar(matriculaId, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(matriculaRepository.findById(matriculaId).orElseThrow().getStatus())
                    .isEqualTo(StatusMatricula.PENDENTE);
            return null;
        });
    }

    @Test
    @DisplayName("turma sem vaga responde 409 sem alterar estado nem gravar evento")
    void turmaSemVagaResponde409() {
        Long turmaId = criarTurmaPorHttp(municipioA, 1);
        inscreverPorHttp(municipioA, turmaId, true);
        inscreverPorHttp(municipioA, turmaId, true);
        String token = tokenCom(municipioA, List.of("*"));
        assertThat(alocar(turmaId, token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta = alocar(turmaId, token);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(matriculaRepository.findByTurmaId(turmaId)).hasSize(1);
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId)).hasSize(1);
            return null;
        });
    }

    @Test
    @DisplayName("fila vazia responde 409, inclusive ao alocar de novo a mesma inscricao")
    void filaVaziaResponde409() {
        Long turmaSemFila = criarTurmaPorHttp(municipioA, 3);
        Long turmaComUmInscrito = criarTurmaPorHttp(municipioA, 3);
        inscreverPorHttp(municipioA, turmaComUmInscrito, true);
        String token = tokenCom(municipioA, List.of("*"));
        assertThat(alocar(turmaComUmInscrito, token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> semFila = alocar(turmaSemFila, token);
        ResponseEntity<String> mesmaInscricaoDeNovo = alocar(turmaComUmInscrito, token);

        assertThat(semFila.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mesmaInscricaoDeNovo.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("responsavel sem coordenadas responde 422 sem alterar estado nem gravar evento")
    void semCoordenadasResponde422() {
        Long turmaId = criarTurmaPorHttp(municipioA, 3);
        Long inscricaoId = inscreverPorHttp(municipioA, turmaId, false);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta = alocar(turmaId, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(matriculaRepository.findByTurmaId(turmaId)).isEmpty();
            assertThat(turmaRepository.findById(turmaId).orElseThrow().getVagasDisponiveis()).isEqualTo(3);
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId)).hasSize(1);
            assertThat(inscricaoRepository.findById(inscricaoId).orElseThrow().getStatus())
                    .isEqualTo(StatusInscricao.AGUARDANDO);
            return null;
        });
    }

    @Test
    @DisplayName("turma inexistente responde 404 e matricula inexistente responde 404")
    void recursosInexistentesRespondem404() {
        String token = tokenCom(municipioA, List.of("*"));

        assertThat(alocar(999_999L, token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(confirmar(999_999L, token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("alocar sem turmaId responde 400 e JSON ilegivel responde 400")
    void corpoInvalidoResponde400() {
        String token = tokenCom(municipioA, List.of("*"));

        ResponseEntity<String> semTurma = postar(CAMINHO_ALOCAR, new HashMap<>(), token);
        ResponseEntity<String> ilegivel =
                restTemplate.exchange(
                        CAMINHO_ALOCAR,
                        HttpMethod.POST,
                        new HttpEntity<>("{isto nao e json", cabecalhos(token)),
                        String.class);

        assertThat(semTurma.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ilegivel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("sem token os dois endpoints respondem 401")
    void semTokenResponde401() {
        assertThat(alocar(1L, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(confirmar(1L, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("token sem a permissao de cada endpoint responde 403 e nada e alterado")
    void tokenSemPermissaoResponde403() {
        Long turmaId = criarTurmaPorHttp(municipioA, 3);
        inscreverPorHttp(municipioA, turmaId, true);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> alocar = alocar(turmaId, tokenCom(municipioA, List.of(PERMISSAO_CONFIRMAR)));
        ResponseEntity<String> confirmar = confirmar(1L, tokenCom(municipioA, List.of(PERMISSAO_ALOCAR)));

        assertThat(alocar.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(confirmar.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("tokens com exatamente a permissao de cada endpoint respondem 201 e 200")
    void tokenComAPermissaoEspecificaFunciona() throws Exception {
        Long turmaId = criarTurmaPorHttp(municipioA, 3);
        inscreverPorHttp(municipioA, turmaId, true);

        ResponseEntity<String> alocar = alocar(turmaId, tokenCom(municipioA, List.of(PERMISSAO_ALOCAR)));
        long matriculaId = objectMapper.readTree(alocar.getBody()).get("matriculaId").asLong();
        ResponseEntity<String> confirmar =
                confirmar(matriculaId, tokenCom(municipioA, List.of(PERMISSAO_CONFIRMAR)));

        assertThat(alocar.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(confirmar.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("o municipio 2 nao enxerga a turma nem a matricula do municipio 1 e responde 404")
    void municipioBNaoEnxergaDadosDoMunicipioA() {
        Long turmaId = criarTurmaPorHttp(municipioA, 3);
        inscreverPorHttp(municipioA, turmaId, true);
        Long matriculaId = alocarNovaMatricula(municipioA);
        outboxEventRepository.deleteAll();
        String tokenB = tokenCom(municipioB, List.of("*"));

        ResponseEntity<String> alocar = alocar(turmaId, tokenB);
        ResponseEntity<String> confirmar = confirmar(matriculaId, tokenB);

        assertThat(alocar.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(confirmar.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEventRepository.findAll()).isEmpty();
        emTenant(municipioA, () -> {
            assertThat(matriculaRepository.findById(matriculaId).orElseThrow().getStatus())
                    .isEqualTo(StatusMatricula.PENDENTE);
            return null;
        });
    }

    @Test
    @DisplayName("desistir devolve a vaga e a realocacao automatica convoca o proximo da fila no mesmo instante")
    void desistirRealocaAutomaticamenteParaOProximo() throws Exception {
        Long turmaId = criarTurmaPorHttp(municipioA, 1);
        inscreverPorHttp(municipioA, turmaId, true);
        Long segundaInscricao = inscreverPorHttp(municipioA, turmaId, true);
        String token = tokenCom(municipioA, List.of("*"));
        Long primeiraMatricula = idDe(alocar(turmaId, token), "matriculaId");
        outboxEventRepository.deleteAll();

        ResponseEntity<String> resposta = desistir(primeiraMatricula, token);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(resposta.getBody()).get("vagasDisponiveis").asInt()).isEqualTo(1);
        emTenant(municipioA, () -> {
            Matricula cancelada = matriculaRepository.findById(primeiraMatricula).orElseThrow();
            assertThat(cancelada.getStatus()).isEqualTo(StatusMatricula.CANCELADA);
            assertThat(inscricaoRepository.findById(cancelada.getInscricaoId()).orElseThrow().getStatus())
                    .isEqualTo(StatusInscricao.CANCELADA);
            Matricula nova = matriculaRepository.findByInscricaoId(segundaInscricao).orElseThrow();
            assertThat(nova.getStatus()).isEqualTo(StatusMatricula.PENDENTE);
            assertThat(inscricaoRepository.findById(segundaInscricao).orElseThrow().getStatus())
                    .isEqualTo(StatusInscricao.CONVOCADA);
            assertThat(turmaRepository.findById(turmaId).orElseThrow().getVagasDisponiveis()).isZero();
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId)).isEmpty();
            return null;
        });
        assertThat(outboxEventRepository.findAll())
                .extracting(OutboxEvent::getTipoEvento)
                .containsExactlyInAnyOrder(
                        "vaga.liberada",
                        "matricula.atualizada",
                        "vaga.liberada",
                        "fila.atualizada",
                        "matricula.atualizada");
    }

    @Test
    @DisplayName("desistir com a fila vazia so devolve a vaga, sem nova matricula")
    void desistirSemFilaSoDevolveAVaga() {
        Long matriculaId = alocarNovaMatricula(municipioA);
        Long turmaId = emTenant(municipioA, () -> matriculaRepository.findById(matriculaId).orElseThrow().getTurma().getId());

        ResponseEntity<String> resposta = desistir(matriculaId, tokenCom(municipioA, List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        emTenant(municipioA, () -> {
            assertThat(turmaRepository.findById(turmaId).orElseThrow().getVagasDisponiveis()).isEqualTo(3);
            assertThat(matriculaRepository.findByTurmaId(turmaId))
                    .extracting(Matricula::getStatus)
                    .containsExactly(StatusMatricula.CANCELADA);
            return null;
        });
    }

    @Test
    @DisplayName("desistir duas vezes responde 409 na segunda e a vaga so volta uma vez")
    void desistirDuasVezesResponde409() {
        Long matriculaId = alocarNovaMatricula(municipioA);
        String token = tokenCom(municipioA, List.of("*"));
        desistir(matriculaId, token);
        outboxEventRepository.deleteAll();

        ResponseEntity<String> segunda = desistir(matriculaId, token);

        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("desistir sem a permissao matricula:matricula:cancelar responde 403")
    void desistirSemPermissaoResponde403() {
        Long matriculaId = alocarNovaMatricula(municipioA);

        ResponseEntity<String> resposta = desistir(matriculaId, tokenCom(municipioA, List.of(PERMISSAO_CONFIRMAR)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("creches proximas devolve as turmas da faixa com distancia, so do municipio do token")
    void crechesProximasDevolveTurmasDoMunicipio() throws Exception {
        Long turmaA = criarTurmaPorHttp(municipioA, 2);
        criarTurmaPorHttp(municipioB, 2);

        ResponseEntity<String> resposta =
                restTemplate.exchange(
                        "/api/v1/matricula/creches/proximas?latitude=-23.55&longitude=-46.63&faixaEtaria=MATERNAL&limite=20",
                        HttpMethod.GET,
                        new HttpEntity<>(cabecalhos(tokenCom(municipioA, List.of("matricula:creche:list")))),
                        String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode lista = objectMapper.readTree(resposta.getBody());
        List<Long> turmas = new ArrayList<>();
        lista.forEach(item -> {
            assertThat(item.get("crecheId").asLong()).isEqualTo(crecheDoMunicipioA);
            assertThat(item.get("distanciaKm").asDouble()).isPositive();
            turmas.add(item.get("turmaId").asLong());
        });
        assertThat(turmas).contains(turmaA);
    }

    @Test
    @DisplayName("creches proximas sem latitude responde 400")
    void crechesProximasSemLatitudeResponde400() {
        ResponseEntity<String> resposta =
                restTemplate.exchange(
                        "/api/v1/matricula/creches/proximas?longitude=-46.63&faixaEtaria=MATERNAL",
                        HttpMethod.GET,
                        new HttpEntity<>(cabecalhos(tokenCom(municipioA, List.of("*")))),
                        String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("inscrever de novo a mesma crianca com inscricao ativa responde 409 sem segunda fila")
    void duplaInscricaoDaMesmaCriancaResponde409() {
        Long turmaId = criarTurmaPorHttp(municipioA, 3);
        Long outraTurma = criarTurmaPorHttp(municipioA, 3);
        Long inscricaoId = inscreverPorHttp(municipioA, turmaId, true);
        Long criancaId = emTenant(municipioA, () -> inscricaoRepository.findById(inscricaoId).orElseThrow().getCrianca().getId());

        ResponseEntity<String> resposta = inscreverCriancaPorId(criancaId, outraTurma);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        emTenant(municipioA, () -> {
            assertThat(inscricaoRepository.findByCriancaId(criancaId)).hasSize(1);
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(outraTurma)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("duas requisicoes simultaneas para a mesma crianca: uma 201 e uma 409")
    void duplaInscricaoSimultaneaSoUmaPassa() throws Exception {
        Long turmaId = criarTurmaPorHttp(municipioA, 3);
        Long inscricaoId = inscreverPorHttp(municipioA, turmaId, true);
        Long criancaId = emTenant(municipioA, () -> inscricaoRepository.findById(inscricaoId).orElseThrow().getCrianca().getId());
        emTenant(municipioA, () -> {
            var inscricao = inscricaoRepository.findById(inscricaoId).orElseThrow();
            inscricao.setStatus(StatusInscricao.CANCELADA);
            filaEsperaRepository.delete(filaEsperaRepository.findByInscricaoId(inscricaoId).orElseThrow());
            return null;
        });

        List<HttpStatus> status = emParalelo(2, () -> inscreverCriancaPorId(criancaId, turmaId));

        assertThat(status).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("inscricoes simultaneas na mesma turma recebem posicoes distintas e consecutivas")
    void inscricoesSimultaneasRecebemPosicoesDistintas() throws Exception {
        Long turmaId = criarTurmaPorHttp(municipioA, 3);

        List<HttpStatus> status = emParalelo(4, () -> postarInscricaoInline(turmaId));

        assertThat(status).containsOnly(HttpStatus.CREATED);
        emTenant(municipioA, () -> {
            assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId))
                    .extracting(FilaEspera::getPosicao)
                    .containsExactly(1, 2, 3, 4);
            return null;
        });
    }

    private List<HttpStatus> emParalelo(int quantidade, Supplier<ResponseEntity<String>> requisicao) throws Exception {
        java.util.concurrent.CyclicBarrier largada = new java.util.concurrent.CyclicBarrier(quantidade);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(quantidade);
        try {
            List<java.util.concurrent.Future<ResponseEntity<String>>> futuros = new ArrayList<>();
            for (int i = 0; i < quantidade; i++) {
                futuros.add(executor.submit(() -> {
                    largada.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return requisicao.get();
                }));
            }
            List<HttpStatus> status = new ArrayList<>();
            for (var futuro : futuros) {
                status.add(HttpStatus.valueOf(futuro.get(30, java.util.concurrent.TimeUnit.SECONDS).getStatusCode().value()));
            }
            return status;
        } finally {
            executor.shutdownNow();
        }
    }

    private ResponseEntity<String> inscreverCriancaPorId(Long criancaId, Long turmaId) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("criancaId", criancaId);
        corpo.put("turmaId", turmaId);
        corpo.put("rendaPerCapita", 1234.56);
        corpo.put("criterioVulnerabilidade", true);
        return postar(CAMINHO_INSCRICOES, corpo, tokenCom(municipioA, List.of("*")));
    }

    private ResponseEntity<String> postarInscricaoInline(Long turmaId) {
        Map<String, Object> responsavel = new HashMap<>();
        responsavel.put("nome", "Maria Responsavel");
        responsavel.put("cpf", String.format("%03d.000.000-00", sequenciaCpf.incrementAndGet()));
        responsavel.put("endereco", Map.of("rua", "Rua A", "numero", 10, "bairro", "Centro", "latitude", -23.55, "longitude", -46.63));
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("responsavel", responsavel);
        corpo.put("crianca", Map.of("nome", "Joao Crianca", "dataNascimento", "2024-03-10"));
        corpo.put("turmaId", turmaId);
        corpo.put("rendaPerCapita", 1234.56);
        corpo.put("criterioVulnerabilidade", true);
        return postar(CAMINHO_INSCRICOES, corpo, tokenCom(municipioA, List.of("*")));
    }

    private ResponseEntity<String> desistir(Long matriculaId, String token) {
        return postar("/api/v1/matricula/matriculas/" + matriculaId + "/desistir", null, token);
    }

    private Long alocarNovaMatricula(Long municipioId) {
        Long turmaId = criarTurmaPorHttp(municipioId, 3);
        inscreverPorHttp(municipioId, turmaId, true);
        return idDe(alocar(turmaId, tokenCom(municipioId, List.of("*"))), "matriculaId");
    }

    private Long criarCrechePorHttp(Long municipioId) {
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
        return idDe(postar(CAMINHO_CRECHES, corpo, tokenCom(municipioId, List.of("*"))), "crecheId");
    }

    private Long criarTurmaPorHttp(Long municipioId, int vagasTotais) {
        Long crecheId = municipioId.equals(municipioA) ? crecheDoMunicipioA : criarCrechePorHttp(municipioId);
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("crecheId", crecheId);
        corpo.put("faixaEtaria", "MATERNAL");
        corpo.put("vagasTotais", vagasTotais);
        return idDe(postar(CAMINHO_TURMAS, corpo, tokenCom(municipioId, List.of("*"))), "turmaId");
    }

    private Long inscreverPorHttp(Long municipioId, Long turmaId, boolean comCoordenadas) {
        Map<String, Object> endereco = new HashMap<>();
        endereco.put("rua", "Rua A");
        endereco.put("numero", 10);
        endereco.put("bairro", "Centro");
        if (comCoordenadas) {
            endereco.put("latitude", -23.55);
            endereco.put("longitude", -46.63);
        }
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
        return idDe(postar(CAMINHO_INSCRICOES, corpo, tokenCom(municipioId, List.of("*"))), "inscricaoId");
    }

    private ResponseEntity<String> alocar(Long turmaId, String token) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("turmaId", turmaId);
        return postar(CAMINHO_ALOCAR, corpo, token);
    }

    private ResponseEntity<String> confirmar(Long matriculaId, String token) {
        return postar("/api/v1/matricula/matriculas/" + matriculaId + "/confirmar", null, token);
    }

    private Long idDe(ResponseEntity<String> resposta, String campo) {
        try {
            return objectMapper.readTree(resposta.getBody()).get(campo).asLong();
        } catch (Exception excecao) {
            throw new IllegalStateException("Resposta inesperada: " + resposta.getStatusCode(), excecao);
        }
    }

    private ResponseEntity<String> postar(String caminho, Map<String, Object> corpo, String token) {
        try {
            String json = corpo == null ? null : objectMapper.writeValueAsString(corpo);
            return restTemplate.exchange(
                    caminho, HttpMethod.POST, new HttpEntity<>(json, cabecalhos(token)), String.class);
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

    private JsonNode payloadDe(List<OutboxEvent> eventos, String tipoEvento) throws Exception {
        OutboxEvent evento =
                eventos.stream().filter(e -> e.getTipoEvento().equals(tipoEvento)).findFirst().orElseThrow();
        return objectMapper.readTree(evento.getPayload());
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
}
