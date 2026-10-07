package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.OutboxEventWriter;
import org.alocacreche.core.outbox.OutboxPayloads.FilaEsperaAtualizadaPayload;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Cobre as regras do InscricaoService unico: municipio vindo do token, tenant aplicado antes de
 * qualquer leitura, 404 em vez de excecao generica, score de prioridade (inclusive irmao
 * matriculado), cadastro inline, fila com posicao e evento fila.atualizada. O comportamento com
 * Postgres real e RLS esta em InscricaoControllerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InscricaoService")
class InscricaoServiceTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long CRIANCA_ID = 30L;
    private static final Long TURMA_ID = 10L;
    private static final Long CRECHE_ID = 20L;
    private static final Long INSCRICAO_ID = 50L;
    private static final String CPF = "123.456.789-09";
    private static final double TOLERANCIA = 1e-9;

    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query query;
    @Mock private InscricaoRepository inscricaoRepository;
    @Mock private CriancaRepository criancaRepository;
    @Mock private ResponsavelService responsavelService;
    @Mock private CriancaService criancaService;
    @Mock private MunicipioRepository municipioRepository;
    @Mock private TurmaRepository turmaRepository;
    @Mock private FilaEsperaRepository filaEsperaRepository;
    @Mock private MatriculaConsulta matriculaConsulta;
    @Mock private OutboxEventWriter outboxEventWriter;
    @Mock private FilaRedisService filaRedisService;

    private InscricaoService service;
    private Municipio municipio;
    private Turma turma;
    private Crianca crianca;
    private Responsavel responsavel;

    @BeforeEach
    void setUp() {
        service =
                new InscricaoService(
                        sessaoTenantResolver,
                        entityManager,
                        inscricaoRepository,
                        criancaRepository,
                        responsavelService,
                        criancaService,
                        municipioRepository,
                        turmaRepository,
                        filaEsperaRepository,
                        matriculaConsulta,
                        new CalculadoraScorePrioridade(),
                        outboxEventWriter,
                        filaRedisService);
        municipio = new Municipio();
        municipio.setConfiguracaoPriorizacao(
                ConfiguracaoPriorizacao.builder()
                        .pesoRenda(0.4)
                        .pesoVulnerabilidade(0.3)
                        .pesoDistancia(0.2)
                        .pesoIrmaoMatriculado(0.1)
                        .build());
        turma = turmaDaCreche();
        responsavel = new Responsavel();
        ReflectionTestUtils.setField(responsavel, "id", 5L);
        crianca = novaCrianca(CRIANCA_ID);
        crianca.setResponsavel(responsavel);

        lenient().when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(eq(1), any())).thenReturn(query);
        lenient().when(municipioRepository.findById(MUNICIPIO_ID)).thenReturn(Optional.of(municipio));
        lenient().when(turmaRepository.findById(TURMA_ID)).thenReturn(Optional.of(turma));
        lenient().when(criancaRepository.findById(CRIANCA_ID)).thenReturn(Optional.of(crianca));
        lenient().when(criancaRepository.findByResponsavelId(5L)).thenReturn(List.of(crianca));
        lenient()
                .when(responsavelService.cadastrarNoMunicipio(eq(MUNICIPIO_ID), any(ResponsavelDTO.class)))
                .thenReturn(responsavel);
        lenient()
                .when(criancaService.cadastrarNoMunicipio(eq(MUNICIPIO_ID), eq(responsavel), any(CriancaDTO.class)))
                .thenReturn(crianca);
        lenient()
                .when(inscricaoRepository.saveAndFlush(any(Inscricao.class)))
                .thenAnswer(
                        invocacao -> {
                            Inscricao inscricao = invocacao.getArgument(0);
                            inscricao.setId(INSCRICAO_ID);
                            return inscricao;
                        });
        lenient().when(filaEsperaRepository.findMaiorPosicaoByTurmaId(TURMA_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("aplica o tenant do token antes de ler municipio, turma e crianca")
    void aplicaOTenantAntesDeLer() {
        service.registrarInscricao(dtoPorId("400.00", true));

        InOrder ordem = inOrder(entityManager, query, municipioRepository, turmaRepository, criancaRepository);
        ordem.verify(entityManager).createNativeQuery("SELECT set_config('app.current_tenant', ?1, true)");
        ordem.verify(query).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(query).getSingleResult();
        ordem.verify(municipioRepository).findById(MUNICIPIO_ID);
        ordem.verify(turmaRepository).findById(TURMA_ID);
        ordem.verify(criancaRepository).findById(CRIANCA_ID);
    }

    @Test
    @DisplayName("municipioId do corpo diferente do token lanca NotPermissionException sem ler nada")
    void municipioDiferenteDoTokenNaoLeNada() {
        InscricaoDTO dto =
                new InscricaoDTO(99L, CRIANCA_ID, null, null, TURMA_ID, new BigDecimal("400.00"), true);

        assertThatThrownBy(() -> service.registrarInscricao(dto)).isInstanceOf(NotPermissionException.class);

        verifyNoInteractions(
                entityManager,
                municipioRepository,
                turmaRepository,
                criancaRepository,
                inscricaoRepository,
                outboxEventWriter);
    }

    @Test
    @DisplayName("municipioId ausente no corpo usa o do token")
    void municipioAusenteUsaODoToken() {
        InscricaoDTO dto =
                new InscricaoDTO(null, CRIANCA_ID, null, null, TURMA_ID, new BigDecimal("400.00"), true);

        InscricaoRegistradaResponse resposta = service.registrarInscricao(dto);

        assertThat(resposta.inscricaoId()).isEqualTo(INSCRICAO_ID);
        verify(municipioRepository).findById(MUNICIPIO_ID);
    }

    @Test
    @DisplayName("crianca inexistente lanca RecursoNaoEncontradoException e nao salva nem grava evento")
    void criancaInexistenteLancaNaoEncontrado() {
        when(criancaRepository.findById(CRIANCA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registrarInscricao(dtoPorId("400.00", true)))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verify(inscricaoRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventWriter);
    }

    @Test
    @DisplayName("municipio inexistente lanca RecursoNaoEncontradoException")
    void municipioInexistenteLancaNaoEncontrado() {
        when(municipioRepository.findById(MUNICIPIO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registrarInscricao(dtoPorId("400.00", true)))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verifyNoInteractions(turmaRepository, criancaRepository, inscricaoRepository, outboxEventWriter);
    }

    @Test
    @DisplayName("turma inexistente lanca RecursoNaoEncontradoException sem salvar nada nem gravar evento")
    void turmaInexistenteLancaNaoEncontrado() {
        when(turmaRepository.findById(TURMA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registrarInscricao(dtoPorId("400.00", true)))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verifyNoInteractions(
                criancaRepository, responsavelService, inscricaoRepository, filaEsperaRepository, outboxEventWriter);
    }

    @Test
    @DisplayName("sem irmao matriculado o score soma renda e vulnerabilidade (0,7)")
    void semIrmaoMatriculadoNaoSomaOFatorDeIrmao() {
        InscricaoRegistradaResponse resposta = service.registrarInscricao(dtoPorId("400.00", true));

        assertThat(resposta.scorePrioridade()).isCloseTo(0.7, Offset.offset(TOLERANCIA));
        assertThat(resposta.status()).isEqualTo(StatusInscricao.AGUARDANDO);
    }

    @Test
    @DisplayName("irmao com matricula soma o peso do irmao matriculado (0,1) ao score")
    void irmaoMatriculadoSomaOPeso() {
        prepararIrma(true);

        InscricaoRegistradaResponse resposta = service.registrarInscricao(dtoPorId("400.00", true));

        assertThat(resposta.scorePrioridade()).isCloseTo(0.8, Offset.offset(TOLERANCIA));
    }

    @Test
    @DisplayName("irmao sem matricula nao soma o peso do irmao")
    void irmaoSemMatriculaNaoSoma() {
        prepararIrma(false);

        InscricaoRegistradaResponse resposta = service.registrarInscricao(dtoPorId("400.00", true));

        assertThat(resposta.scorePrioridade()).isCloseTo(0.7, Offset.offset(TOLERANCIA));
    }

    @Test
    @DisplayName("vulnerabilidade nula e tratada como falsa e renda nula soma zero")
    void camposNulosViramZero() {
        InscricaoDTO dto = new InscricaoDTO(null, CRIANCA_ID, null, null, TURMA_ID, null, null);

        InscricaoRegistradaResponse resposta = service.registrarInscricao(dto);

        assertThat(resposta.scorePrioridade()).isCloseTo(0.0, Offset.offset(TOLERANCIA));
    }

    @Test
    @DisplayName("modo inline delega o cadastro aos servicos de Responsavel e Crianca e nao consulta criancaId")
    void modoInlineDelegaOCadastro() {
        InscricaoDTO dto = dtoInline();

        InscricaoRegistradaResponse resposta = service.registrarInscricao(dto);

        assertThat(resposta.inscricaoId()).isEqualTo(INSCRICAO_ID);
        InOrder ordem = inOrder(responsavelService, criancaService);
        ordem.verify(responsavelService).cadastrarNoMunicipio(MUNICIPIO_ID, dto.responsavel());
        ordem.verify(criancaService).cadastrarNoMunicipio(MUNICIPIO_ID, responsavel, dto.crianca());
        verify(criancaRepository, never()).findById(any());
        ArgumentCaptor<Inscricao> inscricaoSalva = ArgumentCaptor.forClass(Inscricao.class);
        verify(inscricaoRepository).saveAndFlush(inscricaoSalva.capture());
        assertThat(inscricaoSalva.getValue().getCrianca()).isSameAs(crianca);
        assertThat(inscricaoSalva.getValue().getMunicipio()).isSameAs(municipio);
    }

    @Test
    @DisplayName("CPF repetido (ConflitoException do ResponsavelService) propaga sem salvar inscricao nem evento")
    void cpfRepetidoPropagaConflito() {
        when(responsavelService.cadastrarNoMunicipio(eq(MUNICIPIO_ID), any(ResponsavelDTO.class)))
                .thenThrow(new ConflitoException("CPF ja cadastrado"));

        assertThatThrownBy(() -> service.registrarInscricao(dtoInline())).isInstanceOf(ConflitoException.class);

        verifyNoInteractions(
                criancaService, inscricaoRepository, filaEsperaRepository, outboxEventWriter, filaRedisService);
    }

    @Test
    @DisplayName("agenda a escrita no Redis com municipio, turma, inscricao e posicao")
    void agendaEscritaNoRedis() {
        when(filaEsperaRepository.findMaiorPosicaoByTurmaId(TURMA_ID)).thenReturn(Optional.of(1));

        service.registrarInscricao(dtoPorId("400.00", true));

        verify(filaRedisService).adicionarAposCommit(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID, 2);
    }

    @Test
    @DisplayName("com a fila vazia a posicao e 1 e a FilaEspera guarda inscricao, turma e municipio")
    void filaVaziaDaPosicaoUm() {
        InscricaoRegistradaResponse resposta = service.registrarInscricao(dtoPorId("400.00", true));

        assertThat(resposta.posicao()).isEqualTo(1);
        ArgumentCaptor<FilaEspera> filaSalva = ArgumentCaptor.forClass(FilaEspera.class);
        verify(filaEsperaRepository).save(filaSalva.capture());
        FilaEspera fila = filaSalva.getValue();
        assertThat(fila.getInscricao().getId()).isEqualTo(INSCRICAO_ID);
        assertThat(fila.getTurmaId()).isEqualTo(TURMA_ID);
        assertThat(fila.getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(fila.getPosicao()).isEqualTo(1);
    }

    @Test
    @DisplayName("a posicao e a maior posicao da turma mais 1")
    void posicaoEMaiorPosicaoMaisUm() {
        when(filaEsperaRepository.findMaiorPosicaoByTurmaId(TURMA_ID)).thenReturn(Optional.of(3));

        InscricaoRegistradaResponse resposta = service.registrarInscricao(dtoPorId("400.00", true));

        assertThat(resposta.posicao()).isEqualTo(4);
        ArgumentCaptor<FilaEspera> filaSalva = ArgumentCaptor.forClass(FilaEspera.class);
        verify(filaEsperaRepository).save(filaSalva.capture());
        assertThat(filaSalva.getValue().getPosicao()).isEqualTo(4);
    }

    @Test
    @DisplayName("grava fila.atualizada uma unica vez com municipio, turma, creche e inscricao")
    void gravaOEventoDeFila() {
        LocalDate antes = LocalDate.now();

        service.registrarInscricao(dtoPorId("400.00", true));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter, times(1))
                .registrar(eq(OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY), payload.capture());
        FilaEsperaAtualizadaPayload evento = (FilaEsperaAtualizadaPayload) payload.getValue();
        assertThat(evento.tipoEvento()).isEqualTo("fila.atualizada");
        assertThat(evento.municipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(evento.turmaId()).isEqualTo(TURMA_ID);
        assertThat(evento.crecheId()).isEqualTo(CRECHE_ID);
        assertThat(evento.inscricaoId()).isEqualTo(INSCRICAO_ID);
        assertThat(evento.dataEntrada()).isBetween(antes, LocalDate.now());
        assertThat(evento.dataSaida()).isNull();
        assertThat(evento.ocorridoEm()).isNotNull();
    }

    @Test
    @DisplayName("falha ao gravar o evento propaga a excecao")
    void falhaNoEventoPropaga() {
        org.mockito.Mockito.doThrow(new IllegalStateException("falha na outbox"))
                .when(outboxEventWriter)
                .registrar(anyString(), any());

        assertThatThrownBy(() -> service.registrarInscricao(dtoPorId("400.00", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("falha na outbox");
    }

    @Test
    @DisplayName("posicao vem do Redis e o tenant e aplicado antes de ler a inscricao")
    void posicaoVemDoRedis() {
        when(inscricaoRepository.findById(INSCRICAO_ID)).thenReturn(Optional.of(inscricaoAguardando()));
        when(filaRedisService.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).thenReturn(Optional.of(3L));

        PosicaoFilaResponse resposta = service.consultarPosicaoNaFila(TURMA_ID, INSCRICAO_ID);

        assertThat(resposta).isEqualTo(new PosicaoFilaResponse(INSCRICAO_ID, TURMA_ID, 3L));
        InOrder ordem = inOrder(query, inscricaoRepository);
        ordem.verify(query).getSingleResult();
        ordem.verify(inscricaoRepository).findById(INSCRICAO_ID);
        verifyNoInteractions(filaEsperaRepository);
    }

    @Test
    @DisplayName("sem a chave no Redis cai para a posicao persistida em FilaEspera")
    void posicaoCaiParaOBanco() {
        when(inscricaoRepository.findById(INSCRICAO_ID)).thenReturn(Optional.of(inscricaoAguardando()));
        when(filaRedisService.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).thenReturn(Optional.empty());
        FilaEspera fila = new FilaEspera();
        fila.setTurmaId(TURMA_ID);
        fila.setPosicao(2);
        when(filaEsperaRepository.findByInscricaoId(INSCRICAO_ID)).thenReturn(Optional.of(fila));

        PosicaoFilaResponse resposta = service.consultarPosicaoNaFila(TURMA_ID, INSCRICAO_ID);

        assertThat(resposta.posicao()).isEqualTo(2L);
    }

    @Test
    @DisplayName("inscricao inexistente ou de outro municipio (RLS) lanca RecursoNaoEncontradoException")
    void posicaoDeInscricaoInvisivelLanca404() {
        when(inscricaoRepository.findById(INSCRICAO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consultarPosicaoNaFila(TURMA_ID, INSCRICAO_ID))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verifyNoInteractions(filaRedisService, filaEsperaRepository);
    }

    @Test
    @DisplayName("inscricao ja alocada (fora da fila) lanca RecursoNaoEncontradoException com mensagem clara")
    void posicaoDeInscricaoAlocadaLanca404() {
        Inscricao inscricao = inscricaoAguardando();
        inscricao.setStatus(StatusInscricao.CONVOCADA);
        when(inscricaoRepository.findById(INSCRICAO_ID)).thenReturn(Optional.of(inscricao));

        assertThatThrownBy(() -> service.consultarPosicaoNaFila(TURMA_ID, INSCRICAO_ID))
                .isInstanceOf(RecursoNaoEncontradoException.class)
                .hasMessageContaining("não está na fila");

        verifyNoInteractions(filaRedisService);
    }

    @Test
    @DisplayName("sem Redis e sem linha de fila na turma pedida lanca RecursoNaoEncontradoException")
    void posicaoSemRedisESemFilaNaTurmaLanca404() {
        when(inscricaoRepository.findById(INSCRICAO_ID)).thenReturn(Optional.of(inscricaoAguardando()));
        when(filaRedisService.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).thenReturn(Optional.empty());
        FilaEspera deOutraTurma = new FilaEspera();
        deOutraTurma.setTurmaId(999L);
        deOutraTurma.setPosicao(1);
        when(filaEsperaRepository.findByInscricaoId(INSCRICAO_ID)).thenReturn(Optional.of(deOutraTurma));

        assertThatThrownBy(() -> service.consultarPosicaoNaFila(TURMA_ID, INSCRICAO_ID))
                .isInstanceOf(RecursoNaoEncontradoException.class)
                .hasMessageContaining("não está na fila");
    }

    private Inscricao inscricaoAguardando() {
        Inscricao inscricao = new Inscricao();
        inscricao.setId(INSCRICAO_ID);
        inscricao.setStatus(StatusInscricao.AGUARDANDO);
        return inscricao;
    }

    private void prepararIrma(boolean matriculada) {
        Crianca irma = novaCrianca(31L);
        irma.setResponsavel(responsavel);
        Inscricao inscricaoDaIrma = new Inscricao();
        ReflectionTestUtils.setField(inscricaoDaIrma, "id", 77L);
        when(criancaRepository.findByResponsavelId(5L)).thenReturn(List.of(crianca, irma));
        when(inscricaoRepository.findByCriancaId(31L)).thenReturn(List.of(inscricaoDaIrma));
        when(matriculaConsulta.existeMatriculaParaInscricao(77L)).thenReturn(matriculada);
    }

    private InscricaoDTO dtoPorId(String renda, boolean vulnerabilidade) {
        return new InscricaoDTO(
                MUNICIPIO_ID, CRIANCA_ID, null, null, TURMA_ID, new BigDecimal(renda), vulnerabilidade);
    }

    private InscricaoDTO dtoInline() {
        Endereco endereco = new Endereco("Rua A", 10, "Centro", "01000-000", "Sao Paulo", "SP", -23.55, -46.63);
        return new InscricaoDTO(
                null,
                null,
                new ResponsavelDTO(
                        "Maria Responsavel", CPF, "11999990000", "maria@example.com", endereco),
                new CriancaDTO("Joao Crianca", LocalDate.of(2024, 3, 10)),
                TURMA_ID,
                new BigDecimal("1234.56"),
                true);
    }

    private Turma turmaDaCreche() {
        Creche creche = new Creche();
        ReflectionTestUtils.setField(creche, "id", CRECHE_ID);
        Turma novaTurma = new Turma();
        ReflectionTestUtils.setField(novaTurma, "id", TURMA_ID);
        novaTurma.setCreche(creche);
        return novaTurma;
    }

    private Crianca novaCrianca(Long id) {
        Crianca nova = new Crianca();
        ReflectionTestUtils.setField(nova, "id", id);
        return nova;
    }
}
