package org.alocacreche.core.inscricao;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cache de leitura da posicao na fila de espera, um ZSET por municipio e turma
 * ({@code fila:{municipioId}:turma:{turmaId}}, membro = inscricaoId, score = posicao). A fonte da
 * verdade e a tabela {@code fila_espera}: este servico nunca derruba a operacao de negocio.
 *
 * <p>As escritas so acontecem depois do commit da transacao em andamento (rollback nao deixa
 * entrada fantasma) e sao fail-open: falha do Redis vira log de aviso, sem dados sensiveis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilaRedisService {

    private final StringRedisTemplate redisTemplate;

    /** Registra a inscricao no ZSET da fila depois do commit da transacao atual. */
    public void adicionarAposCommit(Long municipioId, Long turmaId, Long inscricaoId, int posicao) {
        executarAposCommit(
                "adicionar", municipioId, turmaId, () -> redisTemplate
                        .opsForZSet()
                        .add(chave(municipioId, turmaId), inscricaoId.toString(), posicao));
    }

    /** Remove a inscricao do ZSET da fila depois do commit da transacao atual. */
    public void removerAposCommit(Long municipioId, Long turmaId, Long inscricaoId) {
        executarAposCommit(
                "remover", municipioId, turmaId, () -> redisTemplate
                        .opsForZSet()
                        .remove(chave(municipioId, turmaId), inscricaoId.toString()));
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

    private void executarAposCommit(String operacao, Long municipioId, Long turmaId, Runnable escrita) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executarFailOpen(operacao, municipioId, turmaId, escrita);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executarFailOpen(operacao, municipioId, turmaId, escrita);
            }
        });
    }

    private void executarFailOpen(String operacao, Long municipioId, Long turmaId, Runnable escrita) {
        try {
            escrita.run();
        } catch (RuntimeException excecao) {
            log.warn(
                    "Falha ao {} entrada da fila no Redis (municipioId={}, turmaId={}): {} - o banco segue como fonte da verdade",
                    operacao,
                    municipioId,
                    turmaId,
                    excecao.getClass().getSimpleName());
        }
    }
}
