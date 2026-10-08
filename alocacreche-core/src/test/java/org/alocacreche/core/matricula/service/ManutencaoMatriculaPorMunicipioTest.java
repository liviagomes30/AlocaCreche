package org.alocacreche.core.matricula.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Orquestracao de uma rodada num municipio: expira antes de reconciliar, preenche cada turma ate
 * acabar vaga ou fila e isola a falha de uma matricula ou turma das demais. A prova com Postgres
 * real esta em MatriculaControllerIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManutencaoMatriculaPorMunicipio")
class ManutencaoMatriculaPorMunicipioTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final ConflitoException FILA_VAZIA = new ConflitoException("Fila de espera da turma esta vazia");

    @Mock private ManutencaoMatriculaConsulta consulta;
    @Mock private ExpiracaoMatriculaTransacional expiracao;
    @Mock private AlocacaoVagaService alocacaoVagaService;
    @InjectMocks private ManutencaoMatriculaPorMunicipio manutencao;

    @Test
    @DisplayName("expira as vencidas antes de procurar turmas com vaga, para realocar as vagas devolvidas")
    void expiraAntesDeReconciliar() {
        when(consulta.matriculasComPrazoVencido(MUNICIPIO_ID)).thenReturn(List.of(50L));
        when(consulta.turmasComVagaEFila(MUNICIPIO_ID)).thenReturn(List.of(30L));
        when(alocacaoVagaService.alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, 30L)).thenThrow(FILA_VAZIA);

        manutencao.executar(MUNICIPIO_ID);

        InOrder ordem = inOrder(expiracao, consulta, alocacaoVagaService);
        ordem.verify(expiracao).expirar(MUNICIPIO_ID, 50L);
        ordem.verify(consulta).turmasComVagaEFila(MUNICIPIO_ID);
        ordem.verify(alocacaoVagaService).alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, 30L);
    }

    @Test
    @DisplayName("falha numa expiracao nao impede as outras")
    void falhaNumaExpiracaoNaoImpedeAsOutras() {
        when(consulta.matriculasComPrazoVencido(MUNICIPIO_ID)).thenReturn(List.of(50L, 51L));
        when(expiracao.expirar(MUNICIPIO_ID, 50L)).thenThrow(new IllegalStateException("banco"));

        assertThatCode(() -> manutencao.executar(MUNICIPIO_ID)).doesNotThrowAnyException();

        verify(expiracao).expirar(MUNICIPIO_ID, 51L);
    }

    @Test
    @DisplayName("aloca na mesma turma ate a fila ou a vaga acabar")
    void preencheATurmaAteAcabar() {
        when(consulta.turmasComVagaEFila(MUNICIPIO_ID)).thenReturn(List.of(30L));
        when(alocacaoVagaService.alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, 30L))
                .thenReturn(new MatriculaAlocadaResponse(1L))
                .thenReturn(new MatriculaAlocadaResponse(2L))
                .thenThrow(FILA_VAZIA);

        manutencao.executar(MUNICIPIO_ID);

        verify(alocacaoVagaService, times(3)).alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, 30L);
    }

    @Test
    @DisplayName("falha inesperada numa turma para so aquela turma e segue para a proxima")
    void falhaNumaTurmaNaoImpedeAsOutras() {
        when(consulta.turmasComVagaEFila(MUNICIPIO_ID)).thenReturn(List.of(30L, 31L));
        when(alocacaoVagaService.alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, 30L))
                .thenThrow(new DadoInvalidoException("sem coordenadas"));
        when(alocacaoVagaService.alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, 31L)).thenThrow(FILA_VAZIA);

        assertThatCode(() -> manutencao.executar(MUNICIPIO_ID)).doesNotThrowAnyException();

        verify(alocacaoVagaService, times(1)).alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, 30L);
        verify(alocacaoVagaService).alocarProximoElegivelNoMunicipio(MUNICIPIO_ID, 31L);
    }

    @Test
    @DisplayName("sem vencidas nem turmas pendentes nao expira nem aloca nada")
    void nadaAFazer() {
        when(consulta.matriculasComPrazoVencido(MUNICIPIO_ID)).thenReturn(List.of());
        when(consulta.turmasComVagaEFila(MUNICIPIO_ID)).thenReturn(List.of());

        manutencao.executar(MUNICIPIO_ID);

        verify(expiracao, never()).expirar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(alocacaoVagaService, never())
                .alocarProximoElegivelNoMunicipio(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
