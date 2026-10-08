package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.CrecheRepository;
import org.alocacreche.core.matricula.FaixaEtaria;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prova com Postgres e Redis reais que a fila de espera de uma turma e serializada: inscricoes
 * simultaneas na mesma turma geram posicoes 1..N sem duplicata nem buraco, na ordem de prioridade
 * (score desc, dataInscricao asc, id asc) e com o ZSET do Redis igual ao banco; inscricao e
 * alocacao concorrentes nao convocam fora da ordem do score; e turmas diferentes nao se bloqueiam.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Fila de espera: concorrencia ponta a ponta")
class InscricaoFilaConcorrenciaIT {

    private static final String CAMINHO_INSCRICOES = "/api/v1/inscricao/inscricoes";
    private static final String CAMINHO_ALOCAR = "/api/v1/matricula/vagas/alocar";
    private static final int INSCRICOES_SIMULTANEAS = 8;
    private static final int LIMITE_SEGUNDOS = 60;

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
    @Autowired private FilaEsperaRepository filaEsperaRepository;
    @Autowired private MatriculaRepository matriculaRepository;
    @Autowired private TravaFilaTurma travaFilaTurma;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final AtomicInteger sequenciaCpf = new AtomicInteger(100);

    private Long municipioId;
    private Long crecheId;
    private Long turmaId;

    @BeforeAll
    void prepararMunicipio() {
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
        municipioId = municipioRepository.saveAndFlush(municipio).getId();
        crecheId = criarCreche();
    }

    @BeforeEach
    void prepararTurma() {
        turmaId = criarTurma();
    }

    @RepeatedTest(4)
    @DisplayName("inscricoes simultaneas na mesma turma geram posicoes 1..N, na ordem de prioridade, iguais no Redis")
    void inscricoesSimultaneasGeramFilaConsistente() throws Exception {
        List<Callable<ResponseEntity<String>>> tarefas = new ArrayList<>();
        for (int i = 0; i < INSCRICOES_SIMULTANEAS; i++) {
            tarefas.add(inscricaoComRenda(turmaId, rendaVariada(i), i % 3 == 0));
        }

        for (ResponseEntity<String> resposta : executarSimultaneamente(tarefas)) {
            assertThat(resposta.getStatusCode()).as(resposta.getBody()).isEqualTo(HttpStatus.CREATED);
        }

        Map<Long, Integer> posicoesNoBanco = verificarFilaNoBanco(turmaId, INSCRICOES_SIMULTANEAS);
        assertThat(posicoesNoBanco.values())
                .as("sem alocacao, as posicoes sao exatamente 1..N")
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, INSCRICOES_SIMULTANEAS).boxed().toList());
        assertThat(lerZsetDoRedis(turmaId)).as("ZSET do Redis igual a fila do banco").isEqualTo(posicoesNoBanco);
    }

    @RepeatedTest(3)
    @DisplayName("inscricoes e alocacoes simultaneas na mesma turma nao duplicam posicao nem convocam fora do score")
    void inscricoesEAlocacoesSimultaneasRespeitamOScore() throws Exception {
        int vagas = 3;
        List<Long> melhores = new ArrayList<>();
        for (int i = 0; i < vagas; i++) {
            melhores.add(inscreverEEsperar(turmaId, 100, true));
        }

        List<Callable<ResponseEntity<String>>> tarefas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tarefas.add(inscricaoComRenda(turmaId, 3000, false));
        }
        for (int i = 0; i < vagas; i++) {
            tarefas.add(alocacao(turmaId));
        }

        for (ResponseEntity<String> resposta : executarSimultaneamente(tarefas)) {
            assertThat(resposta.getStatusCode()).as(resposta.getBody()).isEqualTo(HttpStatus.CREATED);
        }

        Set<Long> convocadas =
                emTenant(
                        () ->
                                matriculaRepository.findByTurmaId(turmaId).stream()
                                        .map(Matricula::getInscricaoId)
                                        .collect(Collectors.toSet()));
        assertThat(convocadas)
                .as("so os 3 melhores scores sao convocados")
                .containsExactlyInAnyOrderElementsOf(melhores);
        verificarFilaNoBanco(turmaId, 5);
    }

    @Test
    @DisplayName("turmas diferentes nao se bloqueiam: com a fila de uma turma travada, a outra segue em paralelo")
    void turmasDiferentesNaoSeBloqueiam() throws Exception {
        Long turmaTravada = turmaId;
        Long turmaLivre = criarTurma();
        CountDownLatch travaAdquirida = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> segurandoALock =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        TenantContext.aplicar(entityManager, municipioId);
                                                        travaFilaTurma.travar(municipioId, turmaTravada);
                                                        travaAdquirida.countDown();
                                                        aguardar(liberar);
                                                    }));
            assertThat(travaAdquirida.await(LIMITE_SEGUNDOS, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<String>> naTurmaLivre = executor.submit(inscricaoComRenda(turmaLivre, 400, true));
            Future<ResponseEntity<String>> naTurmaTravada =
                    executor.submit(inscricaoComRenda(turmaTravada, 400, true));

            assertThat(naTurmaLivre.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            try {
                naTurmaTravada.get(2, TimeUnit.SECONDS);
                throw new AssertionError("a inscricao na turma travada deveria esperar a lock");
            } catch (TimeoutException esperado) {
                assertThat(naTurmaTravada.isDone()).isFalse();
            }

            liberar.countDown();
            segurandoALock.get(LIMITE_SEGUNDOS, TimeUnit.SECONDS);
            assertThat(naTurmaTravada.get(LIMITE_SEGUNDOS, TimeUnit.SECONDS).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        } finally {
            liberar.countDown();
            executor.shutdownNow();
        }
        verificarFilaNoBanco(turmaTravada, 1);
        verificarFilaNoBanco(turmaLivre, 1);
    }

    /**
     * Confere no banco: posicoes distintas, consecutivas e crescentes, na ordem (score desc,
     * dataInscricao asc, id asc). Devolve inscricaoId para posicao.
     */
    private Map<Long, Integer> verificarFilaNoBanco(Long turma, int esperadas) {
        return emTenant(
                () -> {
                    List<FilaEspera> porPosicao = filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turma);
                    assertThat(porPosicao).hasSize(esperadas);

                    List<Integer> posicoes = porPosicao.stream().map(FilaEspera::getPosicao).toList();
                    assertThat(posicoes).as("posicoes distintas").doesNotHaveDuplicates();
                    assertThat(posicoes.get(posicoes.size() - 1) - posicoes.get(0) + 1)
                            .as("posicoes consecutivas, sem buracos")
                            .isEqualTo(esperadas);

                    List<Long> idsPorPosicao =
                            porPosicao.stream().map(fila -> fila.getInscricao().getId()).toList();
                    List<Long> idsPorPrioridade =
                            porPosicao.stream()
                                    .map(FilaEspera::getInscricao)
                                    .sorted(
                                            Comparator.comparing(Inscricao::getScorePrioridade)
                                                    .reversed()
                                                    .thenComparing(Inscricao::getDataInscricao)
                                                    .thenComparing(Inscricao::getId))
                                    .map(Inscricao::getId)
                                    .toList();
                    assertThat(idsPorPosicao)
                            .as("ordem das posicoes igual a ordem de prioridade")
                            .isEqualTo(idsPorPrioridade);

                    Map<Long, Integer> mapa = new HashMap<>();
                    porPosicao.forEach(fila -> mapa.put(fila.getInscricao().getId(), fila.getPosicao()));
                    return mapa;
                });
    }

    private Map<Long, Integer> lerZsetDoRedis(Long turma) {
        Map<Long, Integer> mapa = new HashMap<>();
        var entradas = redisTemplate.opsForZSet().rangeWithScores("fila:" + municipioId + ":turma:" + turma, 0, -1);
        assertThat(entradas).isNotNull();
        entradas.forEach(entrada -> mapa.put(Long.valueOf(entrada.getValue()), entrada.getScore().intValue()));
        return mapa;
    }

    private static int rendaVariada(int indice) {
        int[] rendas = {100, 700, 1200, 1800, 3000};
        return rendas[indice % rendas.length];
    }

    private List<ResponseEntity<String>> executarSimultaneamente(List<Callable<ResponseEntity<String>>> tarefas)
            throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tarefas.size());
        try {
            List<Future<ResponseEntity<String>>> futuros = new ArrayList<>();
            for (Callable<ResponseEntity<String>> tarefa : tarefas) {
                futuros.add(
                        executor.submit(
                                () -> {
                                    largada.await();
                                    return tarefa.call();
                                }));
            }
            largada.countDown();
            List<ResponseEntity<String>> respostas = new ArrayList<>();
            for (Future<ResponseEntity<String>> futuro : futuros) {
                respostas.add(futuro.get(LIMITE_SEGUNDOS, TimeUnit.SECONDS));
            }
            return respostas;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            latch.await(LIMITE_SEGUNDOS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupcao) {
            Thread.currentThread().interrupt();
        }
    }

    private Callable<ResponseEntity<String>> inscricaoComRenda(Long turma, int renda, boolean vulnerabilidade) {
        Map<String, Object> corpo = corpoInscricao(novoCpf(), turma);
        corpo.put("rendaPerCapita", renda);
        corpo.put("criterioVulnerabilidade", vulnerabilidade);
        return () -> enviar(HttpMethod.POST, CAMINHO_INSCRICOES, corpo);
    }

    private Callable<ResponseEntity<String>> alocacao(Long turma) {
        return () -> enviar(HttpMethod.POST, CAMINHO_ALOCAR, Map.of("turmaId", turma));
    }

    private long inscreverEEsperar(Long turma, int renda, boolean vulnerabilidade) throws Exception {
        ResponseEntity<String> resposta = inscricaoComRenda(turma, renda, vulnerabilidade).call();
        assertThat(resposta.getStatusCode()).as(resposta.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(resposta.getBody()).get("inscricaoId").asLong();
    }

    private ResponseEntity<String> enviar(HttpMethod metodo, String caminho, Object corpo) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token());
            return restTemplate.exchange(
                    caminho, metodo, new HttpEntity<>(objectMapper.writeValueAsString(corpo), headers), String.class);
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private String token() {
        return stpLogic.createTokenValue(
                1L, "default", 3600, Map.of("municipioId", municipioId, "perms", List.of("*")));
    }

    private String novoCpf() {
        return String.format("%03d.000.000-00", sequenciaCpf.incrementAndGet());
    }

    private Map<String, Object> corpoInscricao(String cpf, Long turma) {
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
        corpo.put("turmaId", turma);
        return corpo;
    }

    private <T> T emTenant(Supplier<T> acao) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            TenantContext.aplicar(entityManager, municipioId);
                            return acao.get();
                        });
    }

    private Long criarCreche() {
        return emTenant(
                () -> {
                    Creche creche = new Creche();
                    creche.setMunicipioId(municipioId);
                    creche.setNome("Creche de Teste");
                    creche.setCapacidadeTotal(50);
                    creche.setEndereco(
                            new Endereco("Rua B", 20, "Centro", "01000-000", "Sao Paulo", "SP", -23.56, -46.64));
                    return crecheRepository.saveAndFlush(creche).getId();
                });
    }

    private Long criarTurma() {
        return emTenant(
                () -> {
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
