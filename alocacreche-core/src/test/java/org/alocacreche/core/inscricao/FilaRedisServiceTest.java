package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Regras do cache de fila: chaves com o municipio, snapshot so depois do commit, argumentos do
 * script (versao, TTL, pares score/membro), fail-open e leitura vazia (nunca excecao) quando o
 * Redis falha. A semantica do script (versao menor ignorada, maior substitui, vazio limpa) esta em
 * InscricaoFilaRedisIT, com Redis real.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FilaRedisService")
class FilaRedisServiceTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long TURMA_ID = 10L;
    private static final Long INSCRICAO_ID = 50L;
    private static final String CHAVE = "fila:7:turma:10";
    private static final String CHAVE_VERSAO = "fila:7:turma:10:versao";
    private static final List<String> CHAVES = List.of(CHAVE, CHAVE_VERSAO);

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSet;

    private FilaRedisService service;

    @BeforeEach
    void setUp() {
        service = new FilaRedisService(redisTemplate, Duration.ofMinutes(5));
    }

    @AfterEach
    void limparSincronizacao() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("as chaves incluem o municipio e a turma; a da versao e irma da fila")
    void chavesIncluemOMunicipio() {
        assertThat(FilaRedisService.chave(MUNICIPIO_ID, TURMA_ID)).isEqualTo(CHAVE);
        assertThat(FilaRedisService.chaveVersao(MUNICIPIO_ID, TURMA_ID)).isEqualTo(CHAVE_VERSAO);
    }

    @Test
    @DisplayName("sem transacao ativa o snapshot e aplicado na hora, com versao, TTL e pares score/membro")
    void semTransacaoAplicaNaHora() {
        service.publicarSnapshotAposCommit(MUNICIPIO_ID, TURMA_ID, 9L, Map.of(INSCRICAO_ID, 2));

        verify(redisTemplate).execute(any(RedisScript.class), eq(CHAVES), eq("9"), eq("300"), eq("2"), eq("50"));
    }

    @Test
    @DisplayName("snapshot vazio envia so versao e TTL (o script limpa a fila)")
    void snapshotVazioEnviaSoVersaoETtl() {
        service.publicarSnapshotAposCommit(MUNICIPIO_ID, TURMA_ID, 4L, Map.of());

        verify(redisTemplate).execute(any(RedisScript.class), eq(CHAVES), eq("4"), eq("300"));
    }

    @Test
    @DisplayName("com transacao ativa o snapshot so e aplicado no afterCommit")
    void comTransacaoAplicaSoDepoisDoCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.publicarSnapshotAposCommit(MUNICIPIO_ID, TURMA_ID, 1L, Map.of(INSCRICAO_ID, 1));

        verifyNoInteractions(redisTemplate);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(redisTemplate).execute(any(RedisScript.class), eq(CHAVES), eq("1"), eq("300"), eq("1"), eq("50"));
    }

    @Test
    @DisplayName("rollback (afterCompletion sem afterCommit) nao escreve no Redis")
    void rollbackNaoEscreve() {
        TransactionSynchronizationManager.initSynchronization();

        service.publicarSnapshotAposCommit(MUNICIPIO_ID, TURMA_ID, 1L, Map.of(INSCRICAO_ID, 1));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sincronizacao -> sincronizacao.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("falha do Redis na escrita nao propaga (fail-open)")
    void falhaNaEscritaNaoPropaga() {
        when(redisTemplate.execute(any(RedisScript.class), eq(CHAVES), eq("1"), eq("300"), eq("1"), eq("50")))
                .thenThrow(new RedisConnectionFailureException("redis fora"));

        service.publicarSnapshotAposCommit(MUNICIPIO_ID, TURMA_ID, 1L, Map.of(INSCRICAO_ID, 1));

        verify(redisTemplate).execute(any(RedisScript.class), eq(CHAVES), eq("1"), eq("300"), eq("1"), eq("50"));
    }

    @Test
    @DisplayName("o TTL configurado vira segundos no script, com minimo de 1")
    void ttlConfiguradoViraSegundos() {
        new FilaRedisService(redisTemplate, Duration.ofMillis(100))
                .publicarSnapshotAposCommit(MUNICIPIO_ID, TURMA_ID, 2L, Map.of());

        verify(redisTemplate).execute(any(RedisScript.class), eq(CHAVES), eq("2"), eq("1"));
    }

    @Test
    @DisplayName("consulta devolve a posicao guardada como score")
    void consultaDevolveAPosicao() {
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        when(zSet.score(CHAVE, "50")).thenReturn(3.0);

        assertThat(service.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).contains(3L);
    }

    @Test
    @DisplayName("consulta devolve vazio quando o membro nao existe")
    void consultaVaziaQuandoAusente() {
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        when(zSet.score(CHAVE, "50")).thenReturn(null);

        assertThat(service.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("consulta devolve vazio, sem excecao, quando o Redis esta indisponivel")
    void consultaVaziaQuandoRedisFora() {
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        when(zSet.score(CHAVE, "50")).thenThrow(new RedisConnectionFailureException("redis fora"));

        assertThat(service.consultarPosicao(MUNICIPIO_ID, TURMA_ID, INSCRICAO_ID)).isEmpty();
    }
}
