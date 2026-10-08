package org.alocacreche.core.inscricao;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cache de leitura da posicao na fila de espera, um ZSET por municipio e turma
 * ({@code fila:{municipioId}:turma:{turmaId}}, membro = inscricaoId, score = posicao) e uma chave
 * irma ({@code ...:versao}) com a versao do ultimo snapshot aplicado. A fonte da verdade e a
 * tabela {@code fila_espera}: este servico nunca derruba a operacao de negocio.
 *
 * <p>Cada transacao que muda a fila publica UM snapshot com a fila inteira da turma (so quem
 * continua nela). O snapshot e aplicado depois do commit, fora do lock da turma, entao dois
 * snapshots consecutivos podem chegar ao Redis fora de ordem; um script Lua atomico so aplica o
 * snapshot cuja versao (ver {@link VersaoFila}) e maior que a guardada, substituindo o ZSET
 * inteiro. Falha do Redis vira log de aviso, sem dados sensiveis (fail-open).
 *
 * <p>Se o Redis estiver fora durante uma escrita, a chave fica velha. Por isso as duas chaves
 * expiram (TTL configuravel, padrao 5 minutos, renovado a cada snapshot): a defasagem maxima nesse
 * caso e o TTL, e depois dele a leitura cai para o banco.
 */
@Slf4j
@Service
public class FilaRedisService {

    private static final String SUFIXO_VERSAO = ":versao";

    /**
     * KEYS[1] = ZSET da fila, KEYS[2] = versao aplicada, ARGV[1] = versao do snapshot, ARGV[2] =
     * TTL em segundos, ARGV[3..] = pares score, membro. Devolve 1 quando aplicou e 0 quando o
     * snapshot era antigo.
     */
    private static final String SCRIPT_SNAPSHOT = """
            local atual = tonumber(redis.call('GET', KEYS[2]))
            if atual ~= nil and tonumber(ARGV[1]) <= atual then
                return 0
            end
            redis.call('DEL', KEYS[1])
            for i = 3, #ARGV, 2 do
                redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[2])
            if #ARGV >= 3 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 1
            """;

    private static final DefaultRedisScript<Long> SNAPSHOT = new DefaultRedisScript<>(SCRIPT_SNAPSHOT, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long ttlSegundos;

    public FilaRedisService(
            StringRedisTemplate redisTemplate, @Value("${alocacreche.fila.redis.ttl:5m}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttlSegundos = Math.max(1, ttl.toSeconds());
    }

    /**
     * Publica o snapshot da fila da turma (inscricaoId -> posicao, so quem esta na fila) depois do
     * commit da transacao em andamento; rollback nao escreve nada. Mapa vazio limpa a fila.
     */
    public void publicarSnapshotAposCommit(
            Long municipioId, Long turmaId, long versao, Map<Long, Integer> posicoes) {
        Map<Long, Integer> copia = Map.copyOf(posicoes);
        executarAposCommit(municipioId, turmaId, () -> aplicarSnapshot(municipioId, turmaId, versao, copia));
    }

    /**
     * Le a posicao no Redis. Vazio quando a chave ou o membro nao existem (cache frio ou expirado)
     * e tambem quando o Redis esta indisponivel: quem chama deve cair para o banco.
     */
    public Optional<Long> consultarPosicao(Long municipioId, Long turmaId, Long inscricaoId) {
        try {
            Double posicao = redisTemplate.opsForZSet().score(chave(municipioId, turmaId), inscricaoId.toString());
            return Optional.ofNullable(posicao).map(Double::longValue);
        } catch (RuntimeException excecao) {
            log.warn(
                    "Redis indisponivel ao consultar a fila (municipioId={}, turmaId={}): {}",
                    municipioId,
                    turmaId,
                    excecao.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static String chave(Long municipioId, Long turmaId) {
        return "fila:" + municipioId + ":turma:" + turmaId;
    }

    static String chaveVersao(Long municipioId, Long turmaId) {
        return chave(municipioId, turmaId) + SUFIXO_VERSAO;
    }

    private void aplicarSnapshot(Long municipioId, Long turmaId, long versao, Map<Long, Integer> posicoes) {
        List<String> argumentos = new ArrayList<>();
        argumentos.add(Long.toString(versao));
        argumentos.add(Long.toString(ttlSegundos));
        posicoes.forEach((inscricaoId, posicao) -> {
            argumentos.add(posicao.toString());
            argumentos.add(inscricaoId.toString());
        });
        Long aplicado = redisTemplate.execute(
                SNAPSHOT,
                List.of(chave(municipioId, turmaId), chaveVersao(municipioId, turmaId)),
                argumentos.toArray());
        if (Long.valueOf(0L).equals(aplicado)) {
            log.debug(
                    "Snapshot antigo da fila descartado (municipioId={}, turmaId={}, versao={})",
                    municipioId,
                    turmaId,
                    versao);
        }
    }

    private void executarAposCommit(Long municipioId, Long turmaId, Runnable escrita) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executarFailOpen(municipioId, turmaId, escrita);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executarFailOpen(municipioId, turmaId, escrita);
            }
        });
    }

    private void executarFailOpen(Long municipioId, Long turmaId, Runnable escrita) {
        try {
            escrita.run();
        } catch (RuntimeException excecao) {
            log.warn(
                    "Falha ao publicar o snapshot da fila no Redis (municipioId={}, turmaId={}): {} - o banco segue como fonte da verdade",
                    municipioId,
                    turmaId,
                    excecao.getClass().getSimpleName());
        }
    }
}
