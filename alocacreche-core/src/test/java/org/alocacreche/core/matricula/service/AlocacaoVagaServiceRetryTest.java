package org.alocacreche.core.matricula.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.matricula.config.AlocacaoRetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Retry real do Spring nos dois caminhos da alocacao (token e municipio explicito): cada um usa o
 * seu proprio @Recover, conflito de versao repete e vira 409, falha de negocio passa direto.
 */
@SpringJUnitConfig(classes = {AlocacaoRetryConfig.class, AlocacaoVagaService.class})
@DisplayName("AlocacaoVagaService com retry")
class AlocacaoVagaServiceRetryTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long TURMA_ID = 30L;

    @Autowired private AlocacaoVagaService service;
    @MockitoBean private AlocacaoVagaTransacional transacional;

    @BeforeEach
    void limpar() {
        reset(transacional);
    }

    @Test
    @DisplayName("no municipio explicito, um conflito de versao seguido de sucesso devolve a segunda tentativa")
    void municipioConflitoSeguidoDeSucesso() {
        when(transacional.executarNoMunicipio(MUNICIPIO_ID, TURMA_ID))
                .thenThrow(new OptimisticLockingFailureException("versao"))
                .thenReturn(new MatriculaAlocadaResponse(99L));

        assertThat(service.alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, TURMA_ID).matriculaId()).isEqualTo(99L);
        verify(transacional, times(2)).executarNoMunicipio(MUNICIPIO_ID, TURMA_ID);
    }

    @Test
    @DisplayName("no municipio explicito, tres conflitos viram ConflitoException")
    void municipioTresConflitosViramConflito() {
        when(transacional.executarNoMunicipio(MUNICIPIO_ID, TURMA_ID))
                .thenThrow(new OptimisticLockingFailureException("versao"));

        assertThatThrownBy(() -> service.alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, TURMA_ID))
                .isInstanceOf(ConflitoException.class)
                .hasMessageContaining("tente novamente");
        verify(transacional, times(AlocacaoVagaService.MAXIMO_TENTATIVAS)).executarNoMunicipio(MUNICIPIO_ID, TURMA_ID);
    }

    @Test
    @DisplayName("no municipio explicito, fila vazia passa direto sem nova tentativa")
    void municipioFalhaDeNegocioNaoRepete() {
        when(transacional.executarNoMunicipio(MUNICIPIO_ID, TURMA_ID))
                .thenThrow(new ConflitoException("Fila de espera da turma esta vazia"));

        assertThatThrownBy(() -> service.alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, TURMA_ID))
                .isInstanceOf(ConflitoException.class)
                .hasMessage("Fila de espera da turma esta vazia");
        verify(transacional, times(1)).executarNoMunicipio(MUNICIPIO_ID, TURMA_ID);
    }

    @Test
    @DisplayName("pelo token, o caminho antigo continua: tres conflitos viram ConflitoException")
    void tokenTresConflitosViramConflito() {
        when(transacional.executar(TURMA_ID)).thenThrow(new OptimisticLockingFailureException("versao"));

        assertThatThrownBy(() -> service.alocarProximoElegivel(TURMA_ID)).isInstanceOf(ConflitoException.class);
        verify(transacional, times(AlocacaoVagaService.MAXIMO_TENTATIVAS)).executar(TURMA_ID);
    }

    @Test
    @DisplayName("pelo token, falha de negocio passa direto sem nova tentativa")
    void tokenFalhaDeNegocioNaoRepete() {
        when(transacional.executar(TURMA_ID)).thenThrow(new DadoInvalidoException("sem coordenadas"));

        assertThatThrownBy(() -> service.alocarProximoElegivel(TURMA_ID)).isInstanceOf(DadoInvalidoException.class);
        verify(transacional, times(1)).executar(TURMA_ID);
    }
}
