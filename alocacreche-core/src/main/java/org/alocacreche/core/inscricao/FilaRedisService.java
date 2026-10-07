package org.alocacreche.core.inscricao;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FilaRedisService {

    private static final String PREFIXO_CHAVE = "fila:turma:";

    private final RedisTemplate<String, String> redisTemplate;

    public void adicionar(Long turmaId, Long inscricaoId, int posicao) {
        String chave = PREFIXO_CHAVE + turmaId;

        redisTemplate.opsForZSet()
                .add(chave, inscricaoId.toString(), posicao);
    }

    public Long consultarPosicao(Long turmaId, Long inscricaoId) {
        String chave = PREFIXO_CHAVE + turmaId;

        Double posicao = redisTemplate.opsForZSet()
                .score(chave, inscricaoId.toString());

        return posicao == null ? null : posicao.longValue();
    }
}