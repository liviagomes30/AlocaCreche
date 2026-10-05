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
import java.util.Optional;
import org.alocacreche.core.comum.seguranca.SessaoTenantResolver;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.InscricaoRepository;
import org.alocacreche.core.inscricao.StatusInscricao;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.outbox.OutboxEventWriter;
import org.alocacreche.core.outbox.OutboxPayloads.MatriculaAtualizadaPayload;
import org.alocacreche.core.outbox.OutboxRabbitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova a confirmacao da matricula (PROD-16, PROD-17): so PENDENTE dentro do prazo vira
 * CONFIRMADA, a inscricao vira MATRICULADA e o evento leva a data de confirmacao e a mesma
 * distancia; qualquer recusa nao deixa estado nem evento.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmacaoMatriculaServiceImpl")
class ConfirmacaoMatriculaServiceImplTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long CRECHE_ID = 20L;
    private static final Long TURMA_ID = 30L;
    private static final Long INSCRICAO_ID = 40L;
    private static final Long MATRICULA_ID = 50L;
    private static final Double DISTANCIA_KM = 3.5;

    @Mock private SessaoTenantResolver sessaoTenantResolver;
    @Mock private EntityManager entityManager;
    @Mock private Query consultaTenant;
    @Mock private MatriculaRepository matriculaRepository;
    @Mock private InscricaoRepository inscricaoRepository;
    @Mock private OutboxEventWriter outboxEventWriter;

    private ConfirmacaoMatriculaServiceImpl service;
    private Matricula matricula;
    private Inscricao inscricao;

    @BeforeEach
    void montarCenario() {
        service =
                new ConfirmacaoMatriculaServiceImpl(
                        sessaoTenantResolver,
                        entityManager,
                        matriculaRepository,
                        inscricaoRepository,
                        outboxEventWriter);

        Creche creche = new Creche();
        ReflectionTestUtils.setField(creche, "id", CRECHE_ID);
        Turma turma = new Turma();
        ReflectionTestUtils.setField(turma, "id", TURMA_ID);
        turma.setCreche(creche);

        matricula = new Matricula();
        ReflectionTestUtils.setField(matricula, "id", MATRICULA_ID);
        matricula.setMunicipioId(MUNICIPIO_ID);
        matricula.setInscricaoId(INSCRICAO_ID);
        matricula.setTurma(turma);
        matricula.setStatus(StatusMatricula.PENDENTE);
        matricula.setDataConvocacao(LocalDateTime.now().minusDays(1));
        matricula.setPrazoConfirmacao(LocalDateTime.now().plusDays(4));
        matricula.setDistanciaKm(DISTANCIA_KM);

        inscricao = new Inscricao();
        inscricao.setId(INSCRICAO_ID);
        inscricao.setStatus(StatusInscricao.CONVOCADA);

        lenient().when(sessaoTenantResolver.municipioIdAtual()).thenReturn(MUNICIPIO_ID);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(consultaTenant);
        lenient().when(consultaTenant.setParameter(eq(1), any())).thenReturn(consultaTenant);
        lenient().when(matriculaRepository.findById(MATRICULA_ID)).thenReturn(Optional.of(matricula));
        lenient().when(inscricaoRepository.findById(INSCRICAO_ID)).thenReturn(Optional.of(inscricao));
    }

    @Test
    @DisplayName("matricula PENDENTE dentro do prazo vira CONFIRMADA com dataConfirmacao igual a agora")
    void confirmaMatriculaPendenteDentroDoPrazo() {
        LocalDateTime antes = LocalDateTime.now();

        MatriculaConfirmadaResponse resposta = service.confirmar(MATRICULA_ID);

        LocalDateTime depois = LocalDateTime.now();
        assertThat(resposta.matriculaId()).isEqualTo(MATRICULA_ID);
        assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.CONFIRMADA);
        assertThat(matricula.getDataConfirmacao()).isBetween(antes, depois);
        verify(matriculaRepository).saveAndFlush(matricula);
    }

    @Test
    @DisplayName("a Inscricao correspondente vira MATRICULADA")
    void inscricaoViraMatriculada() {
        service.confirmar(MATRICULA_ID);

        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.MATRICULADA);
    }

    @Test
    @DisplayName("grava matricula.atualizada CONFIRMADA com dataConfirmacao e o mesmo distanciaKm")
    void gravaEventoDeConfirmacao() {
        service.confirmar(MATRICULA_ID);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
                .registrar(eq(OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY), payload.capture());
        MatriculaAtualizadaPayload evento = (MatriculaAtualizadaPayload) payload.getValue();
        assertThat(evento.tipoEvento()).isEqualTo("matricula.atualizada");
        assertThat(evento.municipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(evento.turmaId()).isEqualTo(TURMA_ID);
        assertThat(evento.crecheId()).isEqualTo(CRECHE_ID);
        assertThat(evento.inscricaoId()).isEqualTo(INSCRICAO_ID);
        assertThat(evento.matriculaId()).isEqualTo(MATRICULA_ID);
        assertThat(evento.status()).isEqualTo("CONFIRMADA");
        assertThat(evento.distanciaKm()).isEqualTo(DISTANCIA_KM);
        assertThat(evento.dataConvocacao()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(evento.dataConfirmacao()).isEqualTo(LocalDate.now());
    }

    @ParameterizedTest(name = "status {0}")
    @EnumSource(
            value = StatusMatricula.class,
            names = {"CONFIRMADA", "EXPIRADA"})
    @DisplayName("matricula que nao esta PENDENTE lanca ConflitoException sem estado nem evento")
    void matriculaNaoPendenteLancaConflito(StatusMatricula status) {
        matricula.setStatus(status);

        assertThatThrownBy(() -> service.confirmar(MATRICULA_ID)).isInstanceOf(ConflitoException.class);

        verificarNenhumEfeito(status);
    }

    @Test
    @DisplayName("prazo vencido lanca ConflitoException sem estado nem evento")
    void prazoVencidoLancaConflito() {
        matricula.setPrazoConfirmacao(LocalDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> service.confirmar(MATRICULA_ID)).isInstanceOf(ConflitoException.class);

        verificarNenhumEfeito(StatusMatricula.PENDENTE);
    }

    @Test
    @DisplayName("matricula inexistente no municipio lanca RecursoNaoEncontradoException sem evento")
    void matriculaInexistenteLancaNaoEncontrado() {
        when(matriculaRepository.findById(MATRICULA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmar(MATRICULA_ID))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verifyNoInteractions(inscricaoRepository, outboxEventWriter);
    }

    @Test
    @DisplayName("TenantContext.aplicar roda com o municipio do token antes de qualquer consulta")
    void aplicaOTenantAntesDeQualquerConsulta() {
        service.confirmar(MATRICULA_ID);

        InOrder ordem = inOrder(entityManager, consultaTenant, matriculaRepository);
        ordem.verify(entityManager).createNativeQuery(contains("set_config"));
        ordem.verify(consultaTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(matriculaRepository).findById(MATRICULA_ID);
    }

    @Test
    @DisplayName("confirmar e transacional, para desfazer a confirmacao se o evento falhar")
    void confirmarEhTransacional() throws NoSuchMethodException {
        Method confirmar = ConfirmacaoMatriculaServiceImpl.class.getMethod("confirmar", Long.class);

        assertThat(confirmar.getAnnotation(Transactional.class)).isNotNull();
        assertThat(confirmar.getAnnotation(Transactional.class).noRollbackFor()).isEmpty();
    }

    private void verificarNenhumEfeito(StatusMatricula statusOriginal) {
        verifyNoInteractions(outboxEventWriter);
        verify(matriculaRepository, never()).saveAndFlush(any(Matricula.class));
        assertThat(matricula.getStatus()).isEqualTo(statusOriginal);
        assertThat(matricula.getDataConfirmacao()).isNull();
        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.CONVOCADA);
    }
}
