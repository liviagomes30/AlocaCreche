package org.alocacreche.relatorios.filaespera;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre FILA-04 (tenant aplicado antes do upsert) e FILA-07/FILA-08 (payload invalido rejeitado sem
 * chamar o repositorio). Mecanica real do upsert/RLS ja e coberta por
 * FilaEsperaInscricaoRepositoryIT (T5) - aqui o repositorio e mockado de proposito, o alvo desta
 * camada e a logica de validacao e orquestracao, nao a persistencia.
 */
@ExtendWith(MockitoExtension.class)
class FilaEsperaAtualizadaListenerTest {

    private static final LocalDate DATA_ENTRADA = LocalDate.of(2026, 1, 10);

    @Mock private FilaEsperaInscricaoRepository repository;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private FilaEsperaAtualizadaListener listener;

    @BeforeEach
    void setUp() {
        listener = new FilaEsperaAtualizadaListener(repository, entityManager);
    }

    @Test
    void payloadValidoSemDataSaidaAplicaTenantEDelegaAoRepositorio() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(1, "7")).thenReturn(query);
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L, "FilaEsperaAtualizada", 7L, 100L, 10L, 500L, DATA_ENTRADA, null, LocalDateTime.now());

        listener.receber(evento);

        verify(entityManager)
                .createNativeQuery("SELECT set_config('app.current_tenant', ?1, true)");
        verify(query).setParameter(1, "7");
        verify(query).getSingleResult();
        verify(repository).upsert(500L, 100L, 10L, 7L, DATA_ENTRADA, null, 1L);
    }

    @Test
    void payloadValidoComDataSaidaAplicaTenantEDelegaAoRepositorio() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(1, "7")).thenReturn(query);
        LocalDate dataSaida = DATA_ENTRADA.plusDays(5);
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        2L, "FilaEsperaAtualizada", 7L, 100L, 10L, 500L, DATA_ENTRADA, dataSaida, LocalDateTime.now());

        listener.receber(evento);

        verify(repository).upsert(500L, 100L, 10L, 7L, DATA_ENTRADA, dataSaida, 2L);
    }

    @Test
    void eventIdAusenteLancaExcecaoSemChamarRepositorio() {
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        null, "FilaEsperaAtualizada", 7L, 100L, 10L, 500L, DATA_ENTRADA, null, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(FilaEsperaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void municipioIdAusenteLancaExcecaoSemChamarRepositorio() {
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L, "FilaEsperaAtualizada", null, 100L, 10L, 500L, DATA_ENTRADA, null, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(FilaEsperaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void turmaIdAusenteLancaExcecaoSemChamarRepositorio() {
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L, "FilaEsperaAtualizada", 7L, null, 10L, 500L, DATA_ENTRADA, null, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(FilaEsperaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void crecheIdAusenteLancaExcecaoSemChamarRepositorio() {
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L, "FilaEsperaAtualizada", 7L, 100L, null, 500L, DATA_ENTRADA, null, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(FilaEsperaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void inscricaoIdAusenteLancaExcecaoSemChamarRepositorio() {
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L, "FilaEsperaAtualizada", 7L, 100L, 10L, null, DATA_ENTRADA, null, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(FilaEsperaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void dataEntradaAusenteLancaExcecaoSemChamarRepositorio() {
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L, "FilaEsperaAtualizada", 7L, 100L, 10L, 500L, null, null, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(FilaEsperaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void dataSaidaAnteriorADataEntradaLancaExcecaoSemChamarRepositorio() {
        LocalDate dataSaidaInvalida = DATA_ENTRADA.minusDays(1);
        FilaEsperaAtualizadaEvent evento =
                new FilaEsperaAtualizadaEvent(
                        1L,
                        "FilaEsperaAtualizada",
                        7L,
                        100L,
                        10L,
                        500L,
                        DATA_ENTRADA,
                        dataSaidaInvalida,
                        LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(FilaEsperaEventoInvalidoException.class);

        verifyNoInteractions(repository);
    }
}
