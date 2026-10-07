package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Regras do cache de fila: chave com o municipio, escrita so depois do commit, fail-open e leitura
 * vazia (nunca excecao) quando o Redis falha. O comportamento com Redis real esta em
 * InscricaoFilaRedisIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FilaRedisService")
class FilaRedisServiceTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long TURMA_ID = 10L;
    private static final Long INSCRICAO_ID = 50L;
    private static final String CHAVE = "fila:7:turma:10";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSet;

    private FilaRedisService service;

    @BeforeEach
    void setUp() {
        service = new FilaRedisService(redisTemplate);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSet);
    }

    @AfterEach
    void limparSincronizacao() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("a chave inclui o municipio e a turma")
    void chaveIncluiOMunicipio() {
        assertThat(FilaRedisService.chave(MUNICIPIO_ID, TURMA_ID)).isEqualTo(CHAVE);
    }

    @Test
    @DisplayName("sem transacao ativa a escrita acontece na hora")
    void semTransacaoEscreveNaHora() {
        service.adicionarAposCommit(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID, 2);

        verify(zSet).add(CHAVE, "50", 2);
    }

    @Test
    @DisplayName("com transacao ativa a escrita so acontece no afterCommit")
    void comTransacaoEscreveSoDepoisDoCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.adicionarAposCommit(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID, 1);

        verifyNoInteractions(zSet);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(zSet).add(CHAVE, "50", 1);
    }

    @Test
    @DisplayName("rollback (afterCompletion sem afterCommit) nao escreve no Redis")
    void rollbackNaoEscreve() {
        TransactionSynchronizationManager.initSynchronization();

        service.adicionarAposCommit(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID, 1);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sincronizacao -> sincronizacao.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(zSet);
    }

    @Test
    @DisplayName("remover usa ZREM no ZSET do municipio e da turma")
    void removerUsaZrem() {
        service.removerAposCommit(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID);

        verify(zSet).remove(CHAVE, "50");
    }

    @Test
    @DisplayName("falha do Redis na escrita nao propaga (fail-open)")
    void falhaNaEscritaNaoPropaga() {
        when(zSet.add(anyString(), anyString(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException("redis fora"));

        service.adicionarAposCommit(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID, 1);

        verify(zSet).add(CHAVE, "50", 1);
    }

    @Test
    @DisplayName("falha do Redis na remocao nao propaga (fail-open)")
    void falhaNaRemocaoNaoPropaga() {
        doThrow(new RedisConnectionFailureException("redis fora")).when(zSet).remove(anyString(), any());

        service.removerAposCommit(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID);

        verify(zSet).remove(CHAVE, "50");
    }

    @Test
    @DisplayName("consulta devolve a posicao guardada como score")
    void consultaDevolveAPosicao() {
        when(zSet.score(CHAVE, "50")).thenReturn(3.0);

        assertThat(service.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).contains(3L);
    }

    @Test
    @DisplayName("consulta devolve vazio quando o membro nao existe")
    void consultaVaziaQuandoAusente() {
        when(zSet.score(CHAVE, "50")).thenReturn(null);

        assertThat(service.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("consulta devolve vazio, sem excecao, quando o Redis esta indisponivel")
    void consultaVaziaQuandoRedisFora() {
        when(zSet.score(CHAVE, "50")).thenThrow(new RedisConnectionFailureException("redis fora"));

        assertThat(service.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).isEmpty();
    }
}
