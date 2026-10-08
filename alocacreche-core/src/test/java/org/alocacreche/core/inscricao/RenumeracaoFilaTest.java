package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova a renumeracao unica da fila: posicoes 1..n na ordem devolvida pela consulta de
 * prioridade, gravacao em lote, agenda do Redis e exigencia de transacao ativa.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RenumeracaoFila")
class RenumeracaoFilaTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long TURMA_ID = 30L;

    @Mock private FilaEsperaRepository filaEsperaRepository;
    @Mock private FilaRedisService filaRedisService;

    private FilaEspera fila(long inscricaoId, int posicaoAtual) {
        Inscricao inscricao = new Inscricao();
        inscricao.setId(inscricaoId);
        FilaEspera fila = new FilaEspera();
        fila.setInscricao(inscricao);
        fila.setPosicao(posicaoAtual);
        return fila;
    }

    @Test
    @DisplayName("atribui 1..n na ordem de prioridade, grava em lote e agenda o Redis")
    void atribuiPosicoesNaOrdemDaConsulta() {
        FilaEspera segunda = fila(2L, 2);
        FilaEspera terceira = fila(3L, 3);
        FilaEspera primeira = fila(1L, 9);
        List<FilaEspera> ordenadas = List.of(segunda, terceira, primeira);
        when(filaEsperaRepository.findByTurmaIdOrdenadaPorPrioridade(TURMA_ID)).thenReturn(ordenadas);

        Map<Long, Integer> posicoes = new RenumeracaoFila(filaEsperaRepository, filaRedisService)
                .renumerar(MUNICIPIO_ID, TURMA_ID);

        assertThat(posicoes).containsExactlyInAnyOrderEntriesOf(Map.of(2L, 1, 3L, 2, 1L, 3));
        assertThat(segunda.getPosicao()).isEqualTo(1);
        assertThat(terceira.getPosicao()).isEqualTo(2);
        assertThat(primeira.getPosicao()).isEqualTo(3);
        InOrder ordem = inOrder(filaEsperaRepository, filaRedisService);
        ordem.verify(filaEsperaRepository).saveAll(ordenadas);
        ordem.verify(filaRedisService).atualizarPosicoesAposCommit(MUNICIPIO_ID, TURMA_ID, posicoes);
    }

    @Test
    @DisplayName("fila vazia devolve mapa vazio sem erro")
    void filaVaziaDevolveMapaVazio() {
        when(filaEsperaRepository.findByTurmaIdOrdenadaPorPrioridade(TURMA_ID)).thenReturn(List.of());

        Map<Long, Integer> posicoes = new RenumeracaoFila(filaEsperaRepository, filaRedisService)
                .renumerar(MUNICIPIO_ID, TURMA_ID);

        assertThat(posicoes).isEmpty();
        verify(filaRedisService).atualizarPosicoesAposCommit(MUNICIPIO_ID, TURMA_ID, Map.of());
    }

    @Test
    @DisplayName("exige transacao ativa (MANDATORY)")
    void exigeTransacaoAtiva() throws Exception {
        Method metodo = RenumeracaoFila.class.getMethod("renumerar", Long.class, Long.class);

        Transactional anotacao = metodo.getAnnotation(Transactional.class);

        assertThat(anotacao).isNotNull();
        assertThat(anotacao.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
