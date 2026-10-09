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
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.service.SessaoTenantResolver;
import org.alocacreche.core.inscricao.Inscricao;
import org.alocacreche.core.inscricao.InscricaoRepository;
import org.alocacreche.core.inscricao.StatusInscricao;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.outbox.service.OutboxEventWriter;
import org.alocacreche.core.outbox.dto.OutboxPayloads.MatriculaAtualizadaPayload;
import org.alocacreche.core.outbox.dto.OutboxPayloads.VagaLiberadaPayload;
import org.alocacreche.core.outbox.config.OutboxRabbitConfig;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova a desistencia: a matricula vira CANCELADA, a vaga volta a turma na mesma transacao, os
 * eventos vaga.liberada e matricula.atualizada sao gravados e o evento interno dispara a
 * realocacao; qualquer recusa nao deixa estado, evento nem realocacao.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DesistenciaMatriculaTransacional")
class DesistenciaMatriculaTransacionalTest {

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
    @Mock private TurmaRepository turmaRepository;
    @Mock private InscricaoRepository inscricaoRepository;
    @Mock private OutboxEventWriter outboxEventWriter;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DesistenciaMatriculaTransacional desistencia;
    private Turma turma;
    private Matricula matricula;
    private Inscricao inscricao;

    @BeforeEach
    void montarCenario() {
        desistencia =
                new DesistenciaMatriculaTransacional(
                        sessaoTenantResolver,
                        entityManager,
                        matriculaRepository,
                        inscricaoRepository,
                        new LiberacaoVaga(turmaRepository, matriculaRepository, outboxEventWriter),
                        eventPublisher);

        Creche creche = new Creche();
        ReflectionTestUtils.setField(creche, "id", CRECHE_ID);
        turma = new Turma();
        ReflectionTestUtils.setField(turma, "id", TURMA_ID);
        turma.setCreche(creche);
        turma.setVagasTotais(5);
        turma.setVagasDisponiveis(0);

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
        lenient().when(matriculaRepository.buscarParaConfirmar(MATRICULA_ID)).thenReturn(Optional.of(matricula));
        lenient().when(inscricaoRepository.findById(INSCRICAO_ID)).thenReturn(Optional.of(inscricao));
    }

    @ParameterizedTest(name = "status {0}")
    @EnumSource(
            value = StatusMatricula.class,
            names = {"PENDENTE", "CONFIRMADA"})
    @DisplayName("matricula que ocupa vaga vira CANCELADA, a inscricao vira CANCELADA e a vaga volta")
    void cancelaEDevolveAVaga(StatusMatricula status) {
        matricula.setStatus(status);
        matricula.setDataConfirmacao(status == StatusMatricula.CONFIRMADA ? LocalDateTime.now() : null);

        MatriculaCanceladaResponse resposta = desistencia.executar(MATRICULA_ID);

        assertThat(resposta).isEqualTo(new MatriculaCanceladaResponse(MATRICULA_ID, TURMA_ID, 1));
        assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.CANCELADA);
        assertThat(matricula.getDataConfirmacao()).isNull();
        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.CANCELADA);
        assertThat(turma.getVagasDisponiveis()).isEqualTo(1);
        verify(turmaRepository).saveAndFlush(turma);
        verify(matriculaRepository).saveAndFlush(matricula);
    }

    @Test
    @DisplayName("grava vaga.liberada com as vagas atualizadas e matricula.atualizada CANCELADA sem dataConfirmacao")
    void gravaOsDoisEventos() {
        desistencia.executar(MATRICULA_ID);

        VagaLiberadaPayload vaga = capturar(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY, VagaLiberadaPayload.class);
        assertThat(vaga.municipioId()).isEqualTo(MUNICIPIO_ID);
        assertThat(vaga.turmaId()).isEqualTo(TURMA_ID);
        assertThat(vaga.crecheId()).isEqualTo(CRECHE_ID);
        assertThat(vaga.vagasDisponiveis()).isEqualTo(1);

        MatriculaAtualizadaPayload evento =
                capturar(OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY, MatriculaAtualizadaPayload.class);
        assertThat(evento.matriculaId()).isEqualTo(MATRICULA_ID);
        assertThat(evento.inscricaoId()).isEqualTo(INSCRICAO_ID);
        assertThat(evento.status()).isEqualTo("CANCELADA");
        assertThat(evento.distanciaKm()).isEqualTo(DISTANCIA_KM);
        assertThat(evento.dataConvocacao()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(evento.dataConfirmacao()).isNull();
    }

    @Test
    @DisplayName("publica VagaLiberadaInternalEvent com municipio e turma para a realocacao automatica")
    void publicaOEventoInterno() {
        desistencia.executar(MATRICULA_ID);

        verify(eventPublisher).publishEvent(new VagaLiberadaInternalEvent(MUNICIPIO_ID, TURMA_ID));
    }

    @ParameterizedTest(name = "status {0}")
    @EnumSource(
            value = StatusMatricula.class,
            names = {"EXPIRADA", "CANCELADA"})
    @DisplayName("matricula que nao ocupa vaga lanca ConflitoException sem estado, evento nem realocacao")
    void matriculaSemVagaLancaConflito(StatusMatricula status) {
        matricula.setStatus(status);

        assertThatThrownBy(() -> desistencia.executar(MATRICULA_ID)).isInstanceOf(ConflitoException.class);

        verificarNenhumEfeito();
        assertThat(matricula.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("turma ja com todas as vagas livres lanca ConflitoException em vez de passar do total")
    void turmaCheiaDeVagasLancaConflito() {
        turma.setVagasDisponiveis(5);

        assertThatThrownBy(() -> desistencia.executar(MATRICULA_ID)).isInstanceOf(ConflitoException.class);

        verificarNenhumEfeito();
        assertThat(turma.getVagasDisponiveis()).isEqualTo(5);
    }

    @Test
    @DisplayName("matricula inexistente no municipio lanca RecursoNaoEncontradoException")
    void matriculaInexistenteLancaNaoEncontrado() {
        when(matriculaRepository.buscarParaConfirmar(MATRICULA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> desistencia.executar(MATRICULA_ID))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verificarNenhumEfeito();
    }

    @Test
    @DisplayName("conflito de versao na turma propaga para o retry, sem evento nem realocacao")
    void conflitoDeVersaoPropaga() {
        doThrow(new OptimisticLockingFailureException("versao")).when(turmaRepository).saveAndFlush(turma);

        assertThatThrownBy(() -> desistencia.executar(MATRICULA_ID))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verifyNoInteractions(outboxEventWriter, eventPublisher);
    }

    @Test
    @DisplayName("aplica o tenant do token antes de travar a matricula")
    void aplicaOTenantAntes() {
        desistencia.executar(MATRICULA_ID);

        InOrder ordem = inOrder(entityManager, consultaTenant, matriculaRepository);
        ordem.verify(entityManager).createNativeQuery(contains("set_config"));
        ordem.verify(consultaTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(matriculaRepository).buscarParaConfirmar(MATRICULA_ID);
    }

    @Test
    @DisplayName("executar e transacional, para desfazer a devolucao da vaga se um evento falhar")
    void executarEhTransacional() throws NoSuchMethodException {
        Method executar = DesistenciaMatriculaTransacional.class.getMethod("executar", Long.class);

        assertThat(executar.getAnnotation(Transactional.class)).isNotNull();
        assertThat(executar.getAnnotation(Transactional.class).noRollbackFor()).isEmpty();
    }

    private void verificarNenhumEfeito() {
        verify(turmaRepository, never()).saveAndFlush(any(Turma.class));
        verify(matriculaRepository, never()).saveAndFlush(any(Matricula.class));
        verifyNoInteractions(outboxEventWriter, eventPublisher);
        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.CONVOCADA);
    }

    private <T> T capturar(String routingKey, Class<T> tipo) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter).registrar(eq(routingKey), payload.capture());
        return tipo.cast(payload.getValue());
    }
}
