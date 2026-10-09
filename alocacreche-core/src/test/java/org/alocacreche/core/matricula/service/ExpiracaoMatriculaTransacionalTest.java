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
import java.time.LocalDateTime;
import java.util.Optional;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova verificarPrazosVencidos por matricula: so PENDENTE com prazo vencido vira EXPIRADA, com a
 * vaga devolvida e os eventos na mesma transacao; o que mudou entre a varredura e a transacao fica
 * como esta.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExpiracaoMatriculaTransacional")
class ExpiracaoMatriculaTransacionalTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long CRECHE_ID = 20L;
    private static final Long TURMA_ID = 30L;
    private static final Long INSCRICAO_ID = 40L;
    private static final Long MATRICULA_ID = 50L;

    @Mock private EntityManager entityManager;
    @Mock private Query consultaTenant;
    @Mock private MatriculaRepository matriculaRepository;
    @Mock private TurmaRepository turmaRepository;
    @Mock private InscricaoRepository inscricaoRepository;
    @Mock private OutboxEventWriter outboxEventWriter;

    private ExpiracaoMatriculaTransacional expiracao;
    private Turma turma;
    private Matricula matricula;
    private Inscricao inscricao;

    @BeforeEach
    void montarCenario() {
        expiracao =
                new ExpiracaoMatriculaTransacional(
                        entityManager,
                        matriculaRepository,
                        inscricaoRepository,
                        new LiberacaoVaga(turmaRepository, matriculaRepository, outboxEventWriter));

        Creche creche = new Creche();
        ReflectionTestUtils.setField(creche, "id", CRECHE_ID);
        turma = new Turma();
        ReflectionTestUtils.setField(turma, "id", TURMA_ID);
        turma.setCreche(creche);
        turma.setVagasTotais(2);
        turma.setVagasDisponiveis(0);

        matricula = new Matricula();
        ReflectionTestUtils.setField(matricula, "id", MATRICULA_ID);
        matricula.setMunicipioId(MUNICIPIO_ID);
        matricula.setInscricaoId(INSCRICAO_ID);
        matricula.setTurma(turma);
        matricula.setStatus(StatusMatricula.PENDENTE);
        matricula.setDataConvocacao(LocalDateTime.now().minusDays(6));
        matricula.setPrazoConfirmacao(LocalDateTime.now().minusMinutes(1));

        inscricao = new Inscricao();
        inscricao.setId(INSCRICAO_ID);
        inscricao.setStatus(StatusInscricao.CONVOCADA);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(consultaTenant);
        lenient().when(consultaTenant.setParameter(eq(1), any())).thenReturn(consultaTenant);
        lenient().when(matriculaRepository.buscarParaConfirmar(MATRICULA_ID)).thenReturn(Optional.of(matricula));
        lenient().when(inscricaoRepository.findById(INSCRICAO_ID)).thenReturn(Optional.of(inscricao));
    }

    @Test
    @DisplayName("PENDENTE vencida vira EXPIRADA, a inscricao vira EXPIRADA e a vaga volta")
    void expiraEDevolveAVaga() {
        assertThat(expiracao.expirar(MUNICIPIO_ID, MATRICULA_ID)).isTrue();

        assertThat(matricula.getStatus()).isEqualTo(StatusMatricula.EXPIRADA);
        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.EXPIRADA);
        assertThat(turma.getVagasDisponiveis()).isEqualTo(1);
        verify(turmaRepository).saveAndFlush(turma);
        verify(matriculaRepository).saveAndFlush(matricula);
    }

    @Test
    @DisplayName("grava vaga.liberada e matricula.atualizada EXPIRADA sem dataConfirmacao")
    void gravaOsEventos() {
        expiracao.expirar(MUNICIPIO_ID, MATRICULA_ID);

        VagaLiberadaPayload vaga = capturar(OutboxRabbitConfig.VAGA_LIBERADA_ROUTING_KEY, VagaLiberadaPayload.class);
        assertThat(vaga.turmaId()).isEqualTo(TURMA_ID);
        assertThat(vaga.vagasDisponiveis()).isEqualTo(1);
        MatriculaAtualizadaPayload evento =
                capturar(OutboxRabbitConfig.MATRICULA_ATUALIZADA_ROUTING_KEY, MatriculaAtualizadaPayload.class);
        assertThat(evento.matriculaId()).isEqualTo(MATRICULA_ID);
        assertThat(evento.status()).isEqualTo("EXPIRADA");
        assertThat(evento.dataConfirmacao()).isNull();
    }

    @ParameterizedTest(name = "status {0}")
    @EnumSource(
            value = StatusMatricula.class,
            names = {"CONFIRMADA", "CANCELADA", "EXPIRADA"})
    @DisplayName("matricula que mudou de status depois da varredura fica como esta")
    void statusAlteradoNaoExpira(StatusMatricula status) {
        matricula.setStatus(status);

        assertThat(expiracao.expirar(MUNICIPIO_ID, MATRICULA_ID)).isFalse();

        verificarNenhumEfeito();
        assertThat(matricula.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("prazo ainda nao vencido nao expira")
    void prazoNaoVencidoNaoExpira() {
        matricula.setPrazoConfirmacao(LocalDateTime.now().plusMinutes(5));

        assertThat(expiracao.expirar(MUNICIPIO_ID, MATRICULA_ID)).isFalse();

        verificarNenhumEfeito();
    }

    @Test
    @DisplayName("matricula que sumiu da vista do municipio nao expira nada")
    void matriculaInexistenteNaoExpira() {
        when(matriculaRepository.buscarParaConfirmar(MATRICULA_ID)).thenReturn(Optional.empty());

        assertThat(expiracao.expirar(MUNICIPIO_ID, MATRICULA_ID)).isFalse();

        verificarNenhumEfeito();
    }

    @Test
    @DisplayName("conflito de versao na turma propaga sem gravar evento")
    void conflitoDeVersaoPropaga() {
        doThrow(new OptimisticLockingFailureException("versao")).when(turmaRepository).saveAndFlush(turma);

        assertThatThrownBy(() -> expiracao.expirar(MUNICIPIO_ID, MATRICULA_ID))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verifyNoInteractions(outboxEventWriter);
    }

    @Test
    @DisplayName("aplica o tenant recebido antes de travar a matricula")
    void aplicaOTenantAntes() {
        expiracao.expirar(MUNICIPIO_ID, MATRICULA_ID);

        InOrder ordem = inOrder(entityManager, consultaTenant, matriculaRepository);
        ordem.verify(entityManager).createNativeQuery(contains("set_config"));
        ordem.verify(consultaTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(matriculaRepository).buscarParaConfirmar(MATRICULA_ID);
    }

    @Test
    @DisplayName("expirar e transacional e a liberacao da vaga so roda dentro de uma transacao")
    void transacoes() throws NoSuchMethodException {
        Method expirar = ExpiracaoMatriculaTransacional.class.getMethod("expirar", Long.class, Long.class);
        Method liberar =
                LiberacaoVaga.class.getMethod(
                        "liberar", Long.class, Matricula.class, StatusMatricula.class, LocalDateTime.class);

        assertThat(expirar.getAnnotation(Transactional.class)).isNotNull();
        assertThat(liberar.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private void verificarNenhumEfeito() {
        verify(turmaRepository, never()).saveAndFlush(any(Turma.class));
        verify(matriculaRepository, never()).saveAndFlush(any(Matricula.class));
        verifyNoInteractions(outboxEventWriter);
        assertThat(inscricao.getStatus()).isEqualTo(StatusInscricao.CONVOCADA);
    }

    private <T> T capturar(String routingKey, Class<T> tipo) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter).registrar(eq(routingKey), payload.capture());
        return tipo.cast(payload.getValue());
    }
}
