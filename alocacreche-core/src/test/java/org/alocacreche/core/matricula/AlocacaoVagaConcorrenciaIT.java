package org.alocacreche.core.matricula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.inscricao.Crianca;
import org.alocacreche.core.inscricao.CriancaRepository;
import org.alocacreche.core.inscricao.FilaEspera;
import org.alocacreche.core.inscricao.FilaEsperaRepository;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.InscricaoRepository;
import org.alocacreche.core.inscricao.Responsavel;
import org.alocacreche.core.inscricao.ResponsavelRepository;
import org.alocacreche.core.matricula.service.AlocacaoVagaService;
import org.alocacreche.core.matricula.service.AlocacaoVagaTransacional;
import org.alocacreche.core.matricula.service.ConflitoException;
import org.alocacreche.core.matricula.service.MatriculaAlocadaResponse;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prova com Postgres real que o lock otimista da {@code Turma} impede a vaga duplicada
 * (PROD-15): duas alocacoes simultaneas para a ultima vaga resultam em uma matricula e um
 * conflito; um conflito de versao provoca nova tentativa e, esgotadas as 3, vira
 * {@link ConflitoException}.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AlocacaoVagaService concorrencia ponta a ponta")
class AlocacaoVagaConcorrenciaIT {

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

    @MockitoBean private SessaoTenantResolver sessaoTenantResolver;
    @MockitoSpyBean private AlocacaoVagaTransacional alocacaoVagaTransacional;

    @Autowired private AlocacaoVagaService alocacaoVagaService;
    @Autowired private MunicipioRepository municipioRepository;
    @Autowired private CrecheRepository crecheRepository;
    @Autowired private TurmaRepository turmaRepository;
    @Autowired private MatriculaRepository matriculaRepository;
    @Autowired private ResponsavelRepository responsavelRepository;
    @Autowired private CriancaRepository criancaRepository;
    @Autowired private InscricaoRepository inscricaoRepository;
    @Autowired private FilaEsperaRepository filaEsperaRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final AtomicInteger sequenciaCpf = new AtomicInteger(100);

    private Long municipioId;

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
    }

    @BeforeEach
    void apontarOTokenParaOMunicipio() {
        when(sessaoTenantResolver.municipioIdAtual()).thenReturn(municipioId);
    }

    @RepeatedTest(5)
    @DisplayName("duas alocacoes simultaneas para a ultima vaga geram 1 matricula, vagasDisponiveis 0 e 1 conflito")
    void duasAlocacoesSimultaneasParaUltimaVaga() throws Exception {
        Long turmaId = semearTurmaComFila(1, 2);
        CyclicBarrier largada = new CyclicBarrier(2);
        Callable<MatriculaAlocadaResponse> alocacao =
                () -> {
                    largada.await(10, TimeUnit.SECONDS);
                    return alocacaoVagaService.alocarProximoElegivel(turmaId);
                };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MatriculaAlocadaResponse> primeira = executor.submit(alocacao);
            Future<MatriculaAlocadaResponse> segunda = executor.submit(alocacao);

            List<Throwable> falhas = new ArrayList<>();
            int sucessos = 0;
            for (Future<MatriculaAlocadaResponse> futuro : List.of(primeira, segunda)) {
                try {
                    assertThat(futuro.get(30, TimeUnit.SECONDS).matriculaId()).isNotNull();
                    sucessos++;
                } catch (java.util.concurrent.ExecutionException excecao) {
                    falhas.add(excecao.getCause());
                }
            }

            assertThat(sucessos).isEqualTo(1);
            assertThat(falhas).hasSize(1);
            assertThat(falhas.get(0)).isInstanceOf(ConflitoException.class);
        } finally {
            executor.shutdownNow();
        }

        emTenant(
                () -> {
                    assertThat(matriculaRepository.findByTurmaId(turmaId)).hasSize(1);
                    assertThat(turmaRepository.findById(turmaId).orElseThrow().getVagasDisponiveis()).isZero();
                    assertThat(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(turmaId)).hasSize(1);
                    return null;
                });
    }

    @Test
    @DisplayName("um conflito de versao provoca nova tentativa e a segunda tentativa conclui a alocacao")
    void conflitoDeVersaoSeguidoDeSucessoTentaDeNovo() {
        Long turmaId = semearTurmaComFila(2, 1);
        doThrow(new OptimisticLockingFailureException("conflito simulado"))
                .doCallRealMethod()
                .when(alocacaoVagaTransacional)
                .executar(turmaId);

        MatriculaAlocadaResponse resposta = alocacaoVagaService.alocarProximoElegivel(turmaId);

        assertThat(resposta.matriculaId()).isNotNull();
        verify(alocacaoVagaTransacional, times(2)).executar(turmaId);
        emTenant(
                () -> {
                    assertThat(matriculaRepository.findByTurmaId(turmaId)).hasSize(1);
                    return null;
                });
    }

    @Test
    @DisplayName("esgotadas as 3 tentativas o conflito de versao vira ConflitoException e nada e gravado")
    void tresConflitosDeVersaoViramConflito() {
        Long turmaId = semearTurmaComFila(2, 1);
        long eventosAntes = outboxEventRepository.count();
        doThrow(new OptimisticLockingFailureException("conflito simulado"))
                .when(alocacaoVagaTransacional)
                .executar(any());

        assertThatThrownBy(() -> alocacaoVagaService.alocarProximoElegivel(turmaId))
                .isInstanceOf(ConflitoException.class);

        verify(alocacaoVagaTransacional, times(3)).executar(turmaId);
        assertThat(outboxEventRepository.count()).isEqualTo(eventosAntes);
        emTenant(
                () -> {
                    assertThat(matriculaRepository.findByTurmaId(turmaId)).isEmpty();
                    assertThat(turmaRepository.findById(turmaId).orElseThrow().getVagasDisponiveis())
                            .isEqualTo(2);
                    return null;
                });
    }

    private Long semearTurmaComFila(int vagas, int inscritos) {
        return emTenant(
                () -> {
                    Creche creche = new Creche();
                    creche.setMunicipioId(municipioId);
                    creche.setNome("Creche de Teste");
                    creche.setCapacidadeTotal(50);
                    creche.setEndereco(new Endereco("Rua B", 20, "Centro", "01000-000", "Sao Paulo", "SP", -23.56, -46.64));
                    creche = crecheRepository.saveAndFlush(creche);

                    Turma turma = new Turma();
                    turma.setMunicipioId(municipioId);
                    turma.setCreche(creche);
                    turma.setFaixaEtaria(FaixaEtaria.BERCARIO);
                    turma.setVagasTotais(vagas);
                    turma.setVagasDisponiveis(vagas);
                    turma = turmaRepository.saveAndFlush(turma);

                    Municipio municipio = municipioRepository.getReferenceById(municipioId);
                    for (int posicao = 1; posicao <= inscritos; posicao++) {
                        Responsavel responsavel = new Responsavel();
                        responsavel.setMunicipioId(municipioId);
                        responsavel.setNome("Responsavel " + posicao);
                        responsavel.setCpf(String.format("%03d.000.000-00", sequenciaCpf.incrementAndGet()));
                        responsavel.setEndereco(
                                new Endereco("Rua A", 10, "Centro", "01000-001", "Sao Paulo", "SP", -23.55, -46.63));
                        responsavel = responsavelRepository.saveAndFlush(responsavel);

                        Crianca crianca = new Crianca();
                        crianca.setMunicipioId(municipioId);
                        crianca.setResponsavel(responsavel);
                        crianca.setNome("Crianca " + posicao);
                        crianca.setDataNascimento(LocalDate.of(2024, 3, 10));
                        crianca = criancaRepository.saveAndFlush(crianca);

                        Inscricao inscricao = new Inscricao();
                        inscricao.setMunicipio(municipio);
                        inscricao.setCrianca(crianca);
                        inscricao = inscricaoRepository.saveAndFlush(inscricao);

                        FilaEspera fila = new FilaEspera();
                        fila.setMunicipioId(municipioId);
                        fila.setInscricao(inscricao);
                        fila.setTurmaId(turma.getId());
                        fila.setPosicao(posicao);
                        filaEsperaRepository.saveAndFlush(fila);
                    }
                    return turma.getId();
                });
    }

    private <T> T emTenant(Supplier<T> acao) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            TenantContext.aplicar(entityManager, municipioId);
                            return acao.get();
                        });
    }
}
