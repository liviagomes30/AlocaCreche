package org.alocacreche.core.matricula.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * O listener aloca o proximo da fila so depois do commit da desistencia, por injecao (nunca
 * {@code this}), e nenhuma falha da realocacao volta para quem desistiu.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlocacaoAutomaticaListener")
class AlocacaoAutomaticaListenerTest {

    private static final VagaLiberadaInternalEvent EVENTO = new VagaLiberadaInternalEvent(7L, 30L);

    @Mock private AlocacaoVagaService alocacaoVagaService;
    @InjectMocks private AlocacaoAutomaticaListener listener;

    @Test
    @DisplayName("aloca o proximo elegivel da turma liberada no municipio do evento, sem depender do token")
    void alocaOProximoDaTurma() {
        when(alocacaoVagaService.alocarProximoElegivelNoMunicipio(7L, 30L)).thenReturn(new MatriculaAlocadaResponse(99L));

        listener.aoLiberarVaga(EVENTO);

        verify(alocacaoVagaService).alocarProximoElegivelNoMunicipio(7L, 30L);
    }

    @Test
    @DisplayName("fila vazia (ConflitoException) nao propaga")
    void filaVaziaNaoPropaga() {
        when(alocacaoVagaService.alocarProximoElegivelNoMunicipio(7L, 30L))
                .thenThrow(new ConflitoException("Fila de espera da turma esta vazia"));

        assertThatCode(() -> listener.aoLiberarVaga(EVENTO)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("falha inesperada nao propaga para a desistencia ja confirmada")
    void falhaInesperadaNaoPropaga() {
        when(alocacaoVagaService.alocarProximoElegivelNoMunicipio(7L, 30L)).thenThrow(new IllegalStateException("banco fora"));

        assertThatCode(() -> listener.aoLiberarVaga(EVENTO)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("escuta so depois do commit e nao abre transacao propria")
    void escutaDepoisDoCommit() throws NoSuchMethodException {
        Method metodo = AlocacaoAutomaticaListener.class.getMethod("aoLiberarVaga", VagaLiberadaInternalEvent.class);

        assertThat(metodo.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(metodo.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("o retry fica no servico chamado e a transacao do alocador e nova (REQUIRES_NEW)")
    void retryEPropagacaoNoLugarCerto() throws NoSuchMethodException {
        Method alocar =
                AlocacaoVagaService.class.getMethod("alocarProximoElegivelNoMunicipio", Long.class, Long.class);
        Method executar =
                AlocacaoVagaTransacional.class.getMethod("executarNoMunicipio", Long.class, Long.class);

        assertThat(alocar.getAnnotation(Retryable.class)).isNotNull();
        assertThat(alocar.getAnnotation(Transactional.class)).isNull();
        assertThat(executar.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
