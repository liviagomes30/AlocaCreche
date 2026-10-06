package org.alocacreche.core.inscricao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.inscricao.FilaEspera;
import org.alocacreche.core.inscricao.FilaEsperaRepository;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.InscricaoRepository;
import org.alocacreche.core.inscricao.Responsavel;
import org.alocacreche.core.inscricao.ResponsavelRepository;
import org.alocacreche.core.inscricao.StatusInscricao;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.OutboxEventWriter;
import org.alocacreche.core.outbox.OutboxPayloads.FilaEsperaAtualizadaPayload;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova as regras de {@code registrarInscricao} (PROD-01 a PROD-05, PROD-07): entidades criadas,
 * posicao na fila, evento {@code fila.atualizada}, recusas sem efeito colateral, tenant aplicado
 * antes de qualquer consulta e ausencia de dado sensivel (LGPD) no log.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("InscricaoServiceImpl.registrarInscricao")
class InscricaoServiceImplTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long TURMA_ID = 10L;
    private static final Long CRECHE_ID = 20L;
    private static final Long INSCRICAO_ID = 50L;
    private static final String CPF = "123.456.789-09";
    private static final BigDecimal RENDA = new BigDecimal("1234.56");

    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query consultaTenant;
    @Mock private TurmaRepository turmaRepository;
    @Mock private ResponsavelRepository responsavelRepository;
    @Mock private InscricaoRepository inscricaoRepository;
    @Mock private FilaEsperaRepository filaEsperaRepository;
    @Mock private MunicipioRepository municipioRepository;
    @Mock private OutboxEventWriter outboxEventWriter;

    private InscricaoServiceImpl service;
    private Municipio municipio;
    private Turma turma;

    @BeforeEach
    void montarCenarioFeliz() {
        service =
                new InscricaoServiceImpl(
                        sessaoTenantResolver,
                        entityManager,
                        turmaRepository,
                        responsavelRepository,
                        inscricaoRepository,
                        filaEsperaRepository,
                        municipioRepository,
                        outboxEventWriter);
        municipio = new Municipio();
        turma = turmaDaCreche();

        lenient().when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(consultaTenant);
        lenient().when(consultaTenant.setParameter(eq(1), any())).thenReturn(consultaTenant);
        lenient().when(turmaRepository.findById(TURMA_ID)).thenReturn(Optional.of(turma));
        lenient().when(responsavelRepository.findByCpf(CPF)).thenReturn(Optional.empty());
        lenient()
                .when(responsavelRepository.saveAndFlush(any(Responsavel.class)))
                .thenAnswer(invocacao -> invocacao.getArgument(0));
        lenient().when(municipioRepository.getReferenceById(MUNICIPIO_ID)).thenReturn(municipio);
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
    @DisplayName("cria Responsavel, Crianca, Inscricao AGUARDANDO e FilaEspera e devolve inscricaoId e posicao")
    void criaAsEntidadesEDevolveInscricaoIdEPosicao() {
        InscricaoCriadaResponse resposta = service.registrarInscricao(requisicao());

        assertThat(resposta.inscricaoId()).isEqualTo(INSCRICAO_ID);
        assertThat(resposta.posicao()).isEqualTo(1);

        ArgumentCaptor<Responsavel> responsavelSalvo = ArgumentCaptor.forClass(Responsavel.class);
        verify(responsavelRepository).saveAndFlush(responsavelSalvo.capture());
        Responsavel responsavel = responsavelSalvo.getValue();
        assertThat(responsavel.getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(responsavel.getCpf()).isEqualTo(CPF);
        assertThat(responsavel.getNome()).isEqualTo("Maria Responsavel");
        assertThat(responsavel.getCriancas()).hasSize(1);
        assertThat(responsavel.getCriancas().get(0).getNome()).isEqualTo("Joao Crianca");
        assertThat(responsavel.getCriancas().get(0).getDataNascimento()).isEqualTo(LocalDate.of(2024, 3, 10));
        assertThat(responsavel.getCriancas().get(0).getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(responsavel.getCriancas().get(0).getResponsavel()).isSameAs(responsavel);

        ArgumentCaptor<Inscricao> inscricaoSalva = ArgumentCaptor.forClass(Inscricao.class);
        verify(inscricaoRepository).saveAndFlush(inscricaoSalva.capture());
        Inscricao inscricao = inscricaoSalva.getValue();
        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.AGUARDANDO);
        assertThat(inscricao.getMunicipio()).isSameAs(municipio);
        assertThat(inscricao.getCrianca()).isSameAs(responsavel.getCriancas().get(0));
        assertThat(inscricao.getRendaPerCapita()).isEqualByComparingTo(RENDA);
        assertThat(inscricao.getCriterioVulnerabilidade()).isTrue();
        assertThat(inscricao.getScorePrioridade()).isNull();

        ArgumentCaptor<FilaEspera> filaSalva = ArgumentCaptor.forClass(FilaEspera.class);
        verify(filaEsperaRepository).save(filaSalva.capture());
        FilaEspera fila = filaSalva.getValue();
        assertThat(fila.getInscricao()).isSameAs(inscricao);
        assertThat(fila.getTurmaId()).isEqualTo(TURMA_ID);
        assertThat(fila.getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(fila.getPosicao()).isEqualTo(1);
    }

    @Test
    @DisplayName("a posicao e a maior posicao da turma mais 1")
    void posicaoEMaiorPosicaoMaisUm() {
        when(filaEsperaRepository.findMaiorPosicaoByTurmaId(TURMA_ID)).thenReturn(Optional.of(3));

        InscricaoCriadaResponse resposta = service.registrarInscricao(requisicao());

        assertThat(resposta.posicao()).isEqualTo(4);
        ArgumentCaptor<FilaEspera> filaSalva = ArgumentCaptor.forClass(FilaEspera.class);
        verify(filaEsperaRepository).save(filaSalva.capture());
        assertThat(filaSalva.getValue().getPosicao()).isEqualTo(4);
    }

    @Test
    @DisplayName("com a fila vazia a posicao e 1")
    void filaVaziaDaPosicaoUm() {
        InscricaoCriadaResponse resposta = service.registrarInscricao(requisicao());

        assertThat(resposta.posicao()).isEqualTo(1);
    }

    @Test
    @DisplayName("grava fila.atualizada com municipio, turma, creche, inscricao, dataEntrada de hoje e dataSaida nula")
    void gravaOEventoDeFila() {
        LocalDate antes = LocalDate.now();

        service.registrarInscricao(requisicao());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
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
    @DisplayName("dataEntrada e ocorridoEm do evento derivam do mesmo instante, mesmo com o relogio avancando a cada leitura")
    void dataEntradaEOcorridoEmUsamOMesmoInstante() {
        LocalDateTime base = LocalDateTime.of(2020, 1, 1, 23, 59, 59);
        AtomicInteger leituras = new AtomicInteger();
        try (MockedStatic<LocalDateTime> relogio =
                mockStatic(LocalDateTime.class, Mockito.CALLS_REAL_METHODS)) {
            relogio.when(LocalDateTime::now)
                    .thenAnswer(invocacao -> base.plusSeconds(leituras.incrementAndGet()));

            service.registrarInscricao(requisicao());
        }

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
                .registrar(eq(OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY), payload.capture());
        FilaEsperaAtualizadaPayload evento = (FilaEsperaAtualizadaPayload) payload.getValue();
        assertThat(evento.dataEntrada()).isEqualTo(evento.ocorridoEm().toLocalDate());
    }

    @Test
    @DisplayName("CPF ja cadastrado lanca ConflitoException sem salvar nada nem gravar evento")
    void cpfRepetidoLancaConflito() {
        when(responsavelRepository.findByCpf(CPF)).thenReturn(Optional.of(new Responsavel()));

        assertThatThrownBy(() -> service.registrarInscricao(requisicao()))
                .isInstanceOf(ConflitoException.class);

        verify(responsavelRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inscricaoRepository, filaEsperaRepository, outboxEventWriter);
    }

    @Test
    @DisplayName("CPF que ja existe em outro municipio (violacao de unicidade) vira ConflitoException sem evento")
    void cpfRepetidoEmOutroMunicipioLancaConflito() {
        when(responsavelRepository.saveAndFlush(any(Responsavel.class)))
                .thenThrow(new DataIntegrityViolationException("responsavel_cpf_key"));

        assertThatThrownBy(() -> service.registrarInscricao(requisicao()))
                .isInstanceOf(ConflitoException.class);

        verifyNoInteractions(inscricaoRepository, filaEsperaRepository, outboxEventWriter);
    }

    @Test
    @DisplayName("turma inexistente lanca RecursoNaoEncontradoException sem salvar nada nem gravar evento")
    void turmaInexistenteLancaNaoEncontrado() {
        when(turmaRepository.findById(TURMA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registrarInscricao(requisicao()))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verifyNoInteractions(
                responsavelRepository, inscricaoRepository, filaEsperaRepository, outboxEventWriter);
    }

    @Test
    @DisplayName("TenantContext.aplicar roda com o municipio do token antes de qualquer consulta")
    void aplicaOTenantAntesDeQualquerConsulta() {
        service.registrarInscricao(requisicao());

        InOrder ordem = inOrder(entityManager, consultaTenant, turmaRepository, responsavelRepository);
        ordem.verify(entityManager).createNativeQuery(org.mockito.ArgumentMatchers.contains("set_config"));
        ordem.verify(consultaTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(turmaRepository).findById(TURMA_ID);
        ordem.verify(responsavelRepository).findByCpf(CPF);
    }

    @Test
    @DisplayName("falha ao gravar o evento propaga a excecao e a operacao e transacional para desfazer tudo")
    void falhaNoEventoPropagaEEhTransacional() throws NoSuchMethodException {
        org.mockito.Mockito.doThrow(new IllegalStateException("falha na outbox"))
                .when(outboxEventWriter)
                .registrar(anyString(), any());

        assertThatThrownBy(() -> service.registrarInscricao(requisicao()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("falha na outbox");

        Transactional transacional =
                InscricaoServiceImpl.class
                        .getMethod("registrarInscricao", RegistrarInscricaoRequest.class)
                        .getAnnotation(Transactional.class);
        assertThat(transacional).isNotNull();
        assertThat(transacional.readOnly()).isFalse();
        assertThat(transacional.noRollbackFor()).isEmpty();
    }

    @Test
    @DisplayName("o log do fluxo nao contem CPF, renda nem criterio de vulnerabilidade")
    void logNaoContemDadoSensivel(CapturedOutput saida) {
        service.registrarInscricao(requisicao());

        assertThat(saida.getAll())
                .doesNotContain(CPF)
                .doesNotContain("123456789")
                .doesNotContain(RENDA.toPlainString())
                .doesNotContainIgnoringCase("vulnerabilidade");
    }

    private Turma turmaDaCreche() {
        Creche creche = new Creche();
        ReflectionTestUtils.setField(creche, "id", CRECHE_ID);
        Turma novaTurma = new Turma();
        ReflectionTestUtils.setField(novaTurma, "id", TURMA_ID);
        novaTurma.setCreche(creche);
        return novaTurma;
    }

    private RegistrarInscricaoRequest requisicao() {
        Endereco endereco = new Endereco("Rua A", 10, "Centro", "01000-000", "Sao Paulo", "SP", -23.55, -46.63);
        return new RegistrarInscricaoRequest(
                new RegistrarInscricaoRequest.ResponsavelRequest(
                        "Maria Responsavel", CPF, "11999990000", "maria@example.com", endereco),
                new RegistrarInscricaoRequest.CriancaRequest("Joao Crianca", LocalDate.of(2024, 3, 10)),
                TURMA_ID,
                RENDA,
                Boolean.TRUE);
    }
}
