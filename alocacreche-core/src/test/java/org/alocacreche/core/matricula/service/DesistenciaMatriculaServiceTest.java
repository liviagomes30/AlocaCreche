package org.alocacreche.core.matricula.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.matricula.config.AlocacaoRetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Retry real do Spring (sem banco): conflito de versao tenta de novo ate 3 vezes e vira 409;
 * falha de negocio nao e repetida.
 */
@SpringJUnitConfig(classes = {AlocacaoRetryConfig.class, DesistenciaMatriculaService.class})
@DisplayName("DesistenciaMatriculaService")
class DesistenciaMatriculaServiceTest {

    private static final Long MATRICULA_ID = 50L;

    @Autowired private DesistenciaMatriculaService service;
    @MockitoBean private DesistenciaMatriculaTransacional transacional;

    @BeforeEach
    void limpar() {
        reset(transacional);
    }

    @Test
    @DisplayName("um conflito de versao seguido de sucesso devolve a resposta da segunda tentativa")
    void conflitoSeguidoDeSucesso() {
        MatriculaCanceladaResponse esperada = new MatriculaCanceladaResponse(MATRICULA_ID, 30L, 1);
        when(transacional.executar(MATRICULA_ID))
                .thenThrow(new OptimisticLockingFailureException("versao"))
                .thenReturn(esperada);

        assertThat(service.desistir(MATRICULA_ID)).isEqualTo(esperada);
        verify(transacional, times(2)).executar(MATRICULA_ID);
    }

    @Test
    @DisplayName("tres conflitos de versao viram ConflitoException")
    void tresConflitosViramConflito() {
        when(transacional.executar(MATRICULA_ID)).thenThrow(new OptimisticLockingFailureException("versao"));

        assertThatThrownBy(() -> service.desistir(MATRICULA_ID)).isInstanceOf(ConflitoException.class);
        verify(transacional, times(DesistenciaMatriculaService.MAXIMO_TENTATIVAS)).executar(MATRICULA_ID);
    }

    @Test
    @DisplayName("falha de negocio passa direto, sem nova tentativa")
    void falhaDeNegocioNaoRepete() {
        when(transacional.executar(MATRICULA_ID)).thenThrow(new RecursoNaoEncontradoException("Matricula nao encontrada"));

        assertThatThrownBy(() -> service.desistir(MATRICULA_ID)).isInstanceOf(RecursoNaoEncontradoException.class);
        verify(transacional, times(1)).executar(MATRICULA_ID);
    }
}
