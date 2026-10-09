package org.alocacreche.relatorios.matricula.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.alocacreche.relatorios.matricula.domain.StatusMatricula;
import org.alocacreche.relatorios.matricula.event.MatriculaAtualizadaEvent;
import org.alocacreche.relatorios.matricula.exception.MatriculaEventoInvalidoException;
import org.alocacreche.relatorios.matricula.repository.MatriculaRelatorioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre a logica de validacao e orquestracao do listener - a mecanica real do upsert/RLS e coberta
 * por MatriculaRelatorioRepositoryIT, aqui o repositorio e mockado de proposito (mesmo padrao de
 * FilaEsperaAtualizadaListenerTest).
 */
@ExtendWith(MockitoExtension.class)
class MatriculaAtualizadaListenerTest {

    private static final LocalDate DATA_CONVOCACAO = LocalDate.of(2026, 1, 10);

    @Mock private MatriculaRelatorioRepository repository;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private MatriculaAtualizadaListener listener;

    @BeforeEach
    void setUp() {
        listener = new MatriculaAtualizadaListener(repository, entityManager);
    }

    @Test
    void payloadValidoPendenteAplicaTenantEDelegaAoRepositorio() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(1, "7")).thenReturn(query);
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        900L,
                        StatusMatricula.PENDENTE,
                        1.5,
                        DATA_CONVOCACAO,
                        null,
                        LocalDateTime.now());

        listener.receber(evento);

        verify(entityManager).createNativeQuery("SELECT set_config('app.current_tenant', ?1, true)");
        verify(query).setParameter(1, "7");
        verify(query).getSingleResult();
        verify(repository)
                .upsert(900L, 500L, 100L, 10L, 7L, "PENDENTE", 1.5, DATA_CONVOCACAO, null, 1L);
    }

    @Test
    void payloadValidoConfirmadaAplicaTenantEDelegaAoRepositorio() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(1, "7")).thenReturn(query);
        LocalDate dataConfirmacao = DATA_CONVOCACAO.plusDays(3);
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        2L,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        901L,
                        StatusMatricula.CONFIRMADA,
                        2.3,
                        DATA_CONVOCACAO,
                        dataConfirmacao,
                        LocalDateTime.now());

        listener.receber(evento);

        verify(repository)
                .upsert(
                        901L, 500L, 100L, 10L, 7L, "CONFIRMADA", 2.3, DATA_CONVOCACAO, dataConfirmacao, 2L);
    }

    @Test
    void eventIdAusenteLancaExcecaoSemChamarRepositorio() {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        null,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        900L,
                        StatusMatricula.PENDENTE,
                        1.5,
                        DATA_CONVOCACAO,
                        null,
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(MatriculaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void municipioIdAusenteLancaExcecaoSemChamarRepositorio() {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        null,
                        100L,
                        10L,
                        500L,
                        900L,
                        StatusMatricula.PENDENTE,
                        1.5,
                        DATA_CONVOCACAO,
                        null,
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(MatriculaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void matriculaIdAusenteLancaExcecaoSemChamarRepositorio() {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        null,
                        StatusMatricula.PENDENTE,
                        1.5,
                        DATA_CONVOCACAO,
                        null,
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(MatriculaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void distanciaKmAusenteLancaExcecaoSemChamarRepositorio() {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        900L,
                        StatusMatricula.PENDENTE,
                        null,
                        DATA_CONVOCACAO,
                        null,
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(MatriculaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void distanciaKmNegativaLancaExcecaoSemChamarRepositorio() {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        900L,
                        StatusMatricula.PENDENTE,
                        -0.1,
                        DATA_CONVOCACAO,
                        null,
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(MatriculaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void statusConfirmadaSemDataConfirmacaoLancaExcecaoSemChamarRepositorio() {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        900L,
                        StatusMatricula.CONFIRMADA,
                        1.5,
                        DATA_CONVOCACAO,
                        null,
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(MatriculaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void statusPendenteComDataConfirmacaoLancaExcecaoSemChamarRepositorio() {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        900L,
                        StatusMatricula.PENDENTE,
                        1.5,
                        DATA_CONVOCACAO,
                        DATA_CONVOCACAO.plusDays(1),
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(MatriculaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void dataConfirmacaoAnteriorADataConvocacaoLancaExcecaoSemChamarRepositorio() {
        MatriculaAtualizadaEvent evento =
                new MatriculaAtualizadaEvent(
                        1L,
                        "MatriculaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        900L,
                        StatusMatricula.CONFIRMADA,
                        1.5,
                        DATA_CONVOCACAO,
                        DATA_CONVOCACAO.minusDays(1),
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(MatriculaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }
}
