package org.alocacreche.relatorios.ocupacao;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre OCUP-04 (tenant aplicado antes do upsert) e OCUP-06/OCUP-07 (payload invalido rejeitado
 * sem chamar o repositorio). Mecanica real do upsert/RLS ja e coberta por
 * OcupacaoTurmaRepositoryIT (T5) - aqui o repositorio e mockado de proposito, o alvo desta camada
 * e a logica de validacao e orquestracao, nao a persistencia.
 */
@ExtendWith(MockitoExtension.class)
class VagaLiberadaListenerTest {

    @Mock private OcupacaoTurmaRepository repository;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private VagaLiberadaListener listener;

    @BeforeEach
    void setUp() {
        listener = new VagaLiberadaListener(repository, entityManager);
    }

    @Test
    void payloadValidoAplicaTenantEDelegaAoRepositorio() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(1, "7")).thenReturn(query);
        VagaLiberadaEvent evento =
                new VagaLiberadaEvent(1L, "VagaLiberada", 7L, 100L, 10L, 5, LocalDateTime.now());

        listener.receber(evento);

        verify(entityManager)
                .createNativeQuery("SELECT set_config('app.current_tenant', ?1, true)");
        verify(query).setParameter(1, "7");
        verify(query).getSingleResult();
        verify(repository).upsert(100L, 7L, 10L, 5, 1L);
    }

    @Test
    void campoObrigatorioFaltandoLancaExcecaoSemChamarRepositorio() {
        VagaLiberadaEvent evento =
                new VagaLiberadaEvent(null, "VagaLiberada", 7L, 100L, 10L, 5, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(EventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void crecheIdAusenteLancaExcecaoSemChamarRepositorio() {
        VagaLiberadaEvent evento =
                new VagaLiberadaEvent(1L, "VagaLiberada", 7L, 100L, null, 5, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(EventoInvalidoException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void vagasDisponiveisNegativoLancaExcecaoSemChamarRepositorio() {
        VagaLiberadaEvent evento =
                new VagaLiberadaEvent(1L, "VagaLiberada", 7L, 100L, 10L, -1, LocalDateTime.now());

        assertThatThrownBy(() -> listener.receber(evento))
                .isInstanceOf(EventoInvalidoException.class);

        verifyNoInteractions(repository);
    }
}
