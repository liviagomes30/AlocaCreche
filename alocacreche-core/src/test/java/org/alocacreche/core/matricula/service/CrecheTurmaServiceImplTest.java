package org.alocacreche.core.matricula.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.CrecheRepository;
import org.alocacreche.core.matricula.FaixaEtaria;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.outbox.OutboxEventWriter;
import org.alocacreche.core.outbox.OutboxPayloads.VagaLiberadaPayload;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova as regras de {@code criarCreche} e {@code criarTurma} (PROD-08, PROD-09, PROD-10): a creche
 * nasce no municipio do token, a turma nasce com {@code vagasDisponiveis} igual a
 * {@code vagasTotais} e grava {@code vaga.liberada}; creche inexistente e vagas invalidas nao
 * deixam efeito algum.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CrecheTurmaServiceImpl")
class CrecheTurmaServiceImplTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long CRECHE_ID = 20L;
    private static final Long TURMA_ID = 30L;

    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query consultaTenant;
    @Mock private CrecheRepository crecheRepository;
    @Mock private TurmaRepository turmaRepository;
    @Mock private OutboxEventWriter outboxEventWriter;

    private CrecheTurmaServiceImpl service;
    private Creche creche;

    @BeforeEach
    void montarCenario() {
        service =
                new CrecheTurmaServiceImpl(
                        sessaoTenantResolver,
                        entityManager,
                        crecheRepository,
                        turmaRepository,
                        outboxEventWriter);
        creche = new Creche();
        ReflectionTestUtils.setField(creche, "id", CRECHE_ID);

        lenient().when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(consultaTenant);
        lenient().when(consultaTenant.setParameter(eq(1), any())).thenReturn(consultaTenant);
        lenient().when(crecheRepository.findById(CRECHE_ID)).thenReturn(Optional.of(creche));
        lenient()
                .when(crecheRepository.saveAndFlush(any(Creche.class)))
                .thenAnswer(
                        invocacao -> {
                            Creche salva = invocacao.getArgument(0);
                            ReflectionTestUtils.setField(salva, "id", CRECHE_ID);
                            return salva;
                        });
        lenient()
                .when(turmaRepository.saveAndFlush(any(Turma.class)))
                .thenAnswer(
                        invocacao -> {
                            Turma salva = invocacao.getArgument(0);
                            ReflectionTestUtils.setField(salva, "id", TURMA_ID);
                            return salva;
                        });
    }

    @Test
    @DisplayName("criarCreche salva a creche no municipio do token e devolve crecheId")
    void criarCrecheSalvaNoMunicipioDoToken() {
        Endereco endereco = new Endereco("Rua B", 20, "Centro", "01000-000", "Sao Paulo", "SP", -23.56, -46.64);

        CrecheCriadaResponse resposta =
                service.criarCreche(new CriarCrecheRequest("Creche Sol", 50, endereco));

        assertThat(resposta.crecheId()).isEqualTo(CRECHE_ID);
        ArgumentCaptor<Creche> salva = ArgumentCaptor.forClass(Creche.class);
        verify(crecheRepository).saveAndFlush(salva.capture());
        assertThat(salva.getValue().getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(salva.getValue().getNome()).isEqualTo("Creche Sol");
        assertThat(salva.getValue().getCapacidadeTotal()).isEqualTo(50);
        assertThat(salva.getValue().getEndereco()).isEqualTo(endereco);
        verifyNoInteractions(outboxEventWriter);
    }

    @Test
    @DisplayName("criarTurma salva a turma com vagasDisponiveis igual a vagasTotais e devolve turmaId")
    void criarTurmaNasceComVagasDisponiveisIguaisAsTotais() {
        TurmaCriadaResponse resposta =
                service.criarTurma(new CriarTurmaRequest(CRECHE_ID, FaixaEtaria.MATERNAL, 5));

        assertThat(resposta.turmaId()).isEqualTo(TURMA_ID);
        ArgumentCaptor<Turma> salva = ArgumentCaptor.forClass(Turma.class);
        verify(turmaRepository).saveAndFlush(salva.capture());
        assertThat(salva.getValue().getMunicipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(salva.getValue().getCreche()).isSameAs(creche);
        assertThat(salva.getValue().getFaixaEtaria()).isEqualTo(FaixaEtaria.MATERNAL);
        assertThat(salva.getValue().getVagasTotais()).isEqualTo(5);
        assertThat(salva.getValue().getVagasDisponiveis()).isEqualTo(5);
    }

    @Test
    @DisplayName("criarTurma grava vaga.liberada com municipio, turma, creche e vagasDisponiveis igual a vagasTotais")
    void criarTurmaGravaOEventoDeVaga() {
        service.criarTurma(new CriarTurmaRequest(CRECHE_ID, FaixaEtaria.BERCARIO, 8));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
                .registrar(eq(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY), payload.capture());
        VagaLiberadaPayload evento = (VagaLiberadaPayload) payload.getValue();
        assertThat(evento.tipoEvento()).isEqualTo("vaga.liberada");
        assertThat(evento.municipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(evento.turmaId()).isEqualTo(TURMA_ID);
        assertThat(evento.crecheId()).isEqualTo(CRECHE_ID);
        assertThat(evento.vagasDisponiveis()).isEqualTo(8);
        assertThat(evento.ocorridoEm()).isBetween(LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
    }

    @Test
    @DisplayName("crecheId inexistente no municipio lanca RecursoNaoEncontradoException sem turma nem evento")
    void crecheInexistenteLancaNaoEncontrado() {
        when(crecheRepository.findById(CRECHE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.criarTurma(new CriarTurmaRequest(CRECHE_ID, FaixaEtaria.BERCARIO, 5)))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verifyNoInteractions(turmaRepository, outboxEventWriter);
    }

    @ParameterizedTest(name = "vagasTotais {0}")
    @ValueSource(ints = {0, -1})
    @DisplayName("vagasTotais zero ou negativo lanca DadoInvalidoException sem turma nem evento")
    void vagasTotaisNaoPositivasLancamDadoInvalido(int vagasTotais) {
        assertThatThrownBy(
                        () ->
                                service.criarTurma(
                                        new CriarTurmaRequest(CRECHE_ID, FaixaEtaria.BERCARIO, vagasTotais)))
                .isInstanceOf(DadoInvalidoException.class);

        verifyNoInteractions(turmaRepository, outboxEventWriter);
    }

    @Test
    @DisplayName("falha ao gravar vaga.liberada propaga a excecao, sem ser engolida")
    void falhaDoGravadorDeEventoPropaga() {
        doThrow(new IllegalStateException("falha na outbox"))
                .when(outboxEventWriter)
                .registrar(eq(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY), any());

        assertThatThrownBy(() -> service.criarTurma(new CriarTurmaRequest(CRECHE_ID, FaixaEtaria.BERCARIO, 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("falha na outbox");
    }

    @Test
    @DisplayName("TenantContext.aplicar roda com o municipio do token antes de qualquer consulta")
    void aplicaOTenantAntesDeQualquerConsulta() {
        service.criarTurma(new CriarTurmaRequest(CRECHE_ID, FaixaEtaria.BERCARIO, 5));
        service.criarCreche(new CriarCrecheRequest("Creche Sol", 50, new Endereco()));

        InOrder ordem = inOrder(entityManager, consultaTenant, crecheRepository);
        ordem.verify(entityManager).createNativeQuery(contains("set_config"));
        ordem.verify(consultaTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(crecheRepository).findById(CRECHE_ID);
        ordem.verify(entityManager).createNativeQuery(contains("set_config"));
        ordem.verify(consultaTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(crecheRepository).saveAndFlush(any(Creche.class));
    }

    @Test
    @DisplayName("as duas operacoes sao transacionais, para desfazer a turma se o evento falhar")
    void operacoesSaoTransacionais() throws NoSuchMethodException {
        Method criarCreche = CrecheTurmaServiceImpl.class.getMethod("criarCreche", CriarCrecheRequest.class);
        Method criarTurma = CrecheTurmaServiceImpl.class.getMethod("criarTurma", CriarTurmaRequest.class);

        assertThat(criarCreche.getAnnotation(Transactional.class)).isNotNull();
        assertThat(criarTurma.getAnnotation(Transactional.class)).isNotNull();
        assertThat(criarTurma.getAnnotation(Transactional.class).noRollbackFor()).isEmpty();
    }
}
