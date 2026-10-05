package org.alocacreche.core.matricula.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.geolocalizacao.GeolocalizacaoService;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.inscricao.Crianca;
import org.alocacreche.core.inscricao.FilaEspera;
import org.alocacreche.core.inscricao.FilaEsperaRepository;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.Responsavel;
import org.alocacreche.core.inscricao.StatusInscricao;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.alocacreche.core.outbox.OutboxEventWriter;
import org.alocacreche.core.outbox.OutboxPayloads.FilaEsperaAtualizadaPayload;
import org.alocacreche.core.outbox.OutboxPayloads.MatriculaAtualizadaPayload;
import org.alocacreche.core.outbox.OutboxPayloads.VagaLiberadaPayload;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova a alocacao transacional da vaga (PROD-11 a PROD-14): a matricula PENDENTE nasce para o
 * primeiro da fila com o prazo do municipio, a vaga e a fila mudam juntas, os tres eventos sao
 * gravados e qualquer recusa (404, 409, 422) nao deixa estado nem evento.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlocacaoVagaTransacional")
class AlocacaoVagaTransacionalTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long CRECHE_ID = 20L;
    private static final Long TURMA_ID = 30L;
    private static final Long INSCRICAO_ID = 40L;
    private static final Long MATRICULA_ID = 50L;
    private static final Double DISTANCIA_KM = 3.5;
    private static final int PRAZO_DIAS = 5;

    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query consultaTenant;
    @Mock private TurmaRepository turmaRepository;
    @Mock private FilaEsperaRepository filaEsperaRepository;
    @Mock private MatriculaRepository matriculaRepository;
    @Mock private MunicipioRepository municipioRepository;
    @Mock private GeolocalizacaoService geolocalizacaoService;
    @Mock private OutboxEventWriter outboxEventWriter;

    private AlocacaoVagaTransacional alocacao;
    private Turma turma;
    private Creche creche;
    private Responsavel responsavel;
    private Inscricao inscricao;
    private FilaEspera primeiroDaFila;
    private Endereco enderecoResponsavel;
    private Endereco enderecoCreche;

    @BeforeEach
    void montarCenario() {
        alocacao =
                new AlocacaoVagaTransacional(
                        sessaoTenantResolver,
                        entityManager,
                        turmaRepository,
                        filaEsperaRepository,
                        matriculaRepository,
                        municipioRepository,
                        geolocalizacaoService,
                        outboxEventWriter);

        enderecoCreche = new Endereco("Rua B", 20, "Centro", "01000-000", "Sao Paulo", "SP", -23.56, -46.64);
        creche = new Creche();
        ReflectionTestUtils.setField(creche, "id", CRECHE_ID);
        creche.setEndereco(enderecoCreche);

        turma = new Turma();
        ReflectionTestUtils.setField(turma, "id", TURMA_ID);
        turma.setCreche(creche);
        turma.setVagasTotais(5);
        turma.setVagasDisponiveis(3);

        enderecoResponsavel = new Endereco("Rua A", 10, "Centro", "01000-001", "Sao Paulo", "SP", -23.55, -46.63);
        responsavel = new Responsavel();
        responsavel.setEndereco(enderecoResponsavel);
        Crianca crianca = new Crianca();
        crianca.setResponsavel(responsavel);
        inscricao = new Inscricao();
        inscricao.setId(INSCRICAO_ID);
        inscricao.setCrianca(crianca);
        inscricao.setStatus(StatusInscricao.AGUARDANDO);

        primeiroDaFila = new FilaEspera();
        primeiroDaFila.setInscricao(inscricao);
        primeiroDaFila.setTurmaId(TURMA_ID);
        primeiroDaFila.setPosicao(1);
        primeiroDaFila.setDataEntrada(LocalDateTime.now().minusDays(2));

        Inscricao outraInscricao = new Inscricao();
        outraInscricao.setId(41L);
        FilaEspera segundoDaFila = new FilaEspera();
        segundoDaFila.setInscricao(outraInscricao);
        segundoDaFila.setTurmaId(TURMA_ID);
        segundoDaFila.setPosicao(2);

        Municipio municipio = new Municipio();
        municipio.setId(MUNICIPIO_ID);
        municipio.setPrazoConfirmacaoDias(PRAZO_DIAS);

        lenient().when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(consultaTenant);
        lenient().when(consultaTenant.setParameter(eq(1), any())).thenReturn(consultaTenant);
        lenient().when(turmaRepository.findById(TURMA_ID)).thenReturn(Optional.of(turma));
        lenient()
                .when(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(TURMA_ID))
                .thenReturn(List.of(primeiroDaFila, segundoDaFila));
        lenient().when(municipioRepository.findById(MUNICIPIO_ID)).thenReturn(Optional.of(municipio));
        lenient()
                .when(geolocalizacaoService.calcularDistanciaEmQuilometros(any(), any()))
                .thenReturn(DISTANCIA_KM);
        lenient()
                .when(matriculaRepository.saveAndFlush(any(Matricula.class)))
                .thenAnswer(
                        invocacao -> {
                            Matricula salva = invocacao.getArgument(0);
                            ReflectionTestUtils.setField(salva, "id", MATRICULA_ID);
                            return salva;
                        });
    }

    @Test
    @DisplayName("cria a Matricula PENDENTE para a inscricao de menor posicao e devolve o matriculaId")
    void criaMatriculaPendenteParaOPrimeiroDaFila() {
        LocalDateTime antes = LocalDateTime.now();

        MatriculaAlocadaResponse resposta = alocacao.executar(TURMA_ID);

        LocalDateTime depois = LocalDateTime.now();
        assertThat(resposta.matriculaId()).isEqualTo(MATRICULA_ID);
        ArgumentCaptor<Matricula> salva = ArgumentCaptor.forClass(Matricula.class);
        verify(matriculaRepository).saveAndFlush(salva.capture());
        Matricula matricula = salva.getValue();
        assertThat(matricula.getInscricaoId()).isEqualTo(INSCRICAO_ID);
        assertThat(matricula.getTurma()).isSameAs(turma);
        assertThat(matricula.getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.PENDENTE);
        assertThat(matricula.getDataConvocacao()).isBetween(antes, depois);
        assertThat(matricula.getDataConfirmacao()).isNull();
        assertThat(matricula.getDistanciaKm()).isEqualTo(DISTANCIA_KM);
    }

    @Test
    @DisplayName("o prazoConfirmacao e a dataConvocacao mais prazoConfirmacaoDias do municipio")
    void prazoDeConfirmacaoUsaOPrazoDoMunicipio() {
        alocacao.executar(TURMA_ID);

        ArgumentCaptor<Matricula> salva = ArgumentCaptor.forClass(Matricula.class);
        verify(matriculaRepository).saveAndFlush(salva.capture());
        assertThat(salva.getValue().getPrazoConfirmacao())
                .isEqualTo(salva.getValue().getDataConvocacao().plusDays(PRAZO_DIAS));
    }

    @Test
    @DisplayName("decrementa vagasDisponiveis em 1, remove a FilaEspera e muda a Inscricao para CONVOCADA")
    void decrementaVagaRemoveFilaEConvocaInscricao() {
        alocacao.executar(TURMA_ID);

        assertThat(turma.getVagasDisponiveis()).isEqualTo(2);
        verify(turmaRepository).saveAndFlush(turma);
        verify(filaEsperaRepository).delete(primeiroDaFila);
        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.CONVOCADA);
    }

    @Test
    @DisplayName("a vaga e gravada antes da matricula, para o conflito de versao abortar antes de qualquer insert")
    void gravaAVagaAntesDaMatricula() {
        alocacao.executar(TURMA_ID);

        InOrder ordem = inOrder(turmaRepository, matriculaRepository);
        ordem.verify(turmaRepository).saveAndFlush(turma);
        ordem.verify(matriculaRepository).saveAndFlush(any(Matricula.class));
    }

    @Test
    @DisplayName("calcula a distancia entre o endereco do responsavel e o da creche")
    void calculaDistanciaEntreResponsavelECreche() {
        alocacao.executar(TURMA_ID);

        verify(geolocalizacaoService).calcularDistanciaEmQuilometros(enderecoResponsavel, enderecoCreche);
    }

    @Test
    @DisplayName("grava vaga.liberada com o novo vagasDisponiveis")
    void gravaVagaLiberadaComONovoSaldo() {
        alocacao.executar(TURMA_ID);

        VagaLiberadaPayload evento =
                capturar(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY, VagaLiberadaPayload.class);
        assertThat(evento.tipoEvento()).isEqualTo("vaga.liberada");
        assertThat(evento.municipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(evento.turmaId()).isEqualTo(TURMA_ID);
        assertThat(evento.crecheId()).isEqualTo(CRECHE_ID);
        assertThat(evento.vagasDisponiveis()).isEqualTo(2);
        assertThat(evento.ocorridoEm()).isBetween(LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
    }

    @Test
    @DisplayName("grava fila.atualizada com dataSaida igual a hoje e a dataEntrada original")
    void gravaFilaAtualizadaComDataDeSaida() {
        alocacao.executar(TURMA_ID);

        FilaEsperaAtualizadaPayload evento =
                capturar(OutboxRabbitConfig.FILA_ATUALIZADA_ROUTING_KEY, FilaEsperaAtualizadaPayload.class);
        assertThat(evento.tipoEvento()).isEqualTo("fila.atualizada");
        assertThat(evento.municipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(evento.turmaId()).isEqualTo(TURMA_ID);
        assertThat(evento.crecheId()).isEqualTo(CRECHE_ID);
        assertThat(evento.inscricaoId()).isEqualTo(INSCRICAO_ID);
        assertThat(evento.dataEntrada()).isEqualTo(LocalDate.now().minusDays(2));
        assertThat(evento.dataSaida()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("grava matricula.atualizada com status PENDENTE, distanciaKm e sem dataConfirmacao")
    void gravaMatriculaAtualizadaPendente() {
        alocacao.executar(TURMA_ID);

        MatriculaAtualizadaPayload evento =
                capturar(OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY, MatriculaAtualizadaPayload.class);
        assertThat(evento.tipoEvento()).isEqualTo("matricula.atualizada");
        assertThat(evento.municipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(evento.turmaId()).isEqualTo(TURMA_ID);
        assertThat(evento.crecheId()).isEqualTo(CRECHE_ID);
        assertThat(evento.inscricaoId()).isEqualTo(INSCRICAO_ID);
        assertThat(evento.matriculaId()).isEqualTo(MATRICULA_ID);
        assertThat(evento.status()).isEqualTo("PENDENTE");
        assertThat(evento.distanciaKm()).isEqualTo(DISTANCIA_KM);
        assertThat(evento.dataConvocacao()).isEqualTo(LocalDate.now());
        assertThat(evento.dataConfirmacao()).isNull();
    }

    static List<Object[]> cenariosSemCoordenadas() {
        Endereco comCoordenadas = new Endereco("Rua", 1, "Centro", "01000-000", "Sao Paulo", "SP", -23.5, -46.6);
        Endereco semLatitude = new Endereco("Rua", 1, "Centro", "01000-000", "Sao Paulo", "SP", null, -46.6);
        Endereco semLongitude = new Endereco("Rua", 1, "Centro", "01000-000", "Sao Paulo", "SP", -23.5, null);
        return List.of(
                new Object[] {"responsavel sem endereco", null, comCoordenadas},
                new Object[] {"responsavel sem latitude", semLatitude, comCoordenadas},
                new Object[] {"responsavel sem longitude", semLongitude, comCoordenadas},
                new Object[] {"creche sem endereco", comCoordenadas, null},
                new Object[] {"creche sem latitude", comCoordenadas, semLatitude},
                new Object[] {"creche sem longitude", comCoordenadas, semLongitude});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cenariosSemCoordenadas")
    @DisplayName("sem coordenadas no responsavel ou na creche lanca DadoInvalidoException sem estado nem evento")
    void semCoordenadasLancaDadoInvalido(String cenario, Endereco doResponsavel, Endereco daCreche) {
        responsavel.setEndereco(doResponsavel);
        creche.setEndereco(daCreche);

        assertThatThrownBy(() -> alocacao.executar(TURMA_ID)).isInstanceOf(DadoInvalidoException.class);

        verificarRecusaSemEfeito();
    }

    @Test
    @DisplayName("turma sem vaga lanca ConflitoException sem estado nem evento")
    void turmaSemVagaLancaConflito() {
        turma.setVagasDisponiveis(0);

        assertThatThrownBy(() -> alocacao.executar(TURMA_ID)).isInstanceOf(ConflitoException.class);

        verificarRecusaSemEfeito();
        assertThat(turma.getVagasDisponiveis()).isZero();
    }

    @Test
    @DisplayName("fila vazia lanca ConflitoException sem estado nem evento")
    void filaVaziaLancaConflito() {
        when(filaEsperaRepository.findByTurmaIdOrderByPosicaoAsc(TURMA_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> alocacao.executar(TURMA_ID)).isInstanceOf(ConflitoException.class);

        verificarRecusaSemEfeito();
        assertThat(turma.getVagasDisponiveis()).isEqualTo(3);
    }

    @Test
    @DisplayName("turma inexistente no municipio lanca RecursoNaoEncontradoException sem estado nem evento")
    void turmaInexistenteLancaNaoEncontrado() {
        when(turmaRepository.findById(TURMA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alocacao.executar(TURMA_ID)).isInstanceOf(RecursoNaoEncontradoException.class);

        verifyNoInteractions(filaEsperaRepository, matriculaRepository, outboxEventWriter);
        verify(turmaRepository, never()).saveAndFlush(any(Turma.class));
    }

    @Test
    @DisplayName("sem vaga e sem coordenadas, a recusa 409 vem antes da 422")
    void conflitoTemPrioridadeSobreCoordenadasAusentes() {
        turma.setVagasDisponiveis(0);
        responsavel.setEndereco(null);

        assertThatThrownBy(() -> alocacao.executar(TURMA_ID)).isInstanceOf(ConflitoException.class);
    }

    @Test
    @DisplayName("TenantContext.aplicar roda com o municipio do token antes de qualquer consulta")
    void aplicaOTenantAntesDeQualquerConsulta() {
        alocacao.executar(TURMA_ID);

        InOrder ordem = inOrder(entityManager, consultaTenant, turmaRepository);
        ordem.verify(entityManager).createNativeQuery(contains("set_config"));
        ordem.verify(consultaTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(turmaRepository).findById(TURMA_ID);
    }

    @Test
    @DisplayName("executar e transacional, para desfazer tudo se um evento falhar")
    void executarEhTransacional() throws NoSuchMethodException {
        Method executar = AlocacaoVagaTransacional.class.getMethod("executar", Long.class);

        assertThat(executar.getAnnotation(Transactional.class)).isNotNull();
        assertThat(executar.getAnnotation(Transactional.class).noRollbackFor()).isEmpty();
    }

    private void verificarRecusaSemEfeito() {
        verify(turmaRepository, never()).saveAndFlush(any(Turma.class));
        verify(filaEsperaRepository, never()).delete(any(FilaEspera.class));
        verifyNoInteractions(matriculaRepository, outboxEventWriter);
        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.AGUARDANDO);
    }

    private <T> T capturar(String routingKey, Class<T> tipo) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter).registrar(eq(routingKey), payload.capture());
        return tipo.cast(payload.getValue());
    }
}
