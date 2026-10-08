package org.alocacreche.core.inscricao;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renumera a fila de espera de uma turma por prioridade (score desc, dataInscricao asc, id asc) e
 * agenda, para depois do commit, a publicacao do snapshot da fila no Redis. Componente unico usado por quem
 * muda a composicao da fila: o enfileiramento (inscricao) e a alocacao da vaga (que retira o
 * primeiro e faz os demais subirem).
 *
 * <p>Custo O(n) por chamada, conhecido e aceito (ADR 0007). Exige transacao ativa e que o chamador
 * ja tenha adquirido {@link TravaFilaTurma} da turma: sem o lock, duas renumeracoes simultaneas
 * nao enxergariam a linha uma da outra, e a versao do snapshot nao seguiria a ordem dos commits. As
 * entradas removidas da fila (alocada) precisam ter sido deletadas antes da chamada: o snapshot
 * contem so quem continua na fila e substitui o ZSET inteiro, o que tambem remove a alocada.
 */
@Component
@RequiredArgsConstructor
public class RenumeracaoFila {

    private final FilaEsperaRepository filaEsperaRepository;
    private final FilaRedisService filaRedisService;
    private final VersaoFila versaoFila;

    /**
     * Atribui as posicoes 1..n na ordem de prioridade e devolve o mapa inscricaoId -> posicao.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Integer> renumerar(Long municipioId, Long turmaId) {
        List<FilaEspera> filasOrdenadas =
                filaEsperaRepository.findByTurmaIdOrdenadaPorPrioridade(turmaId);

        Map<Long, Integer> posicoes = new HashMap<>();
        for (int i = 0; i < filasOrdenadas.size(); i++) {
            FilaEspera filaAtual = filasOrdenadas.get(i);
            int novaPosicao = i + 1;
            filaAtual.setPosicao(novaPosicao);
            posicoes.put(filaAtual.getInscricao().getId(), novaPosicao);
        }

        filaEsperaRepository.saveAll(filasOrdenadas);
        filaRedisService.publicarSnapshotAposCommit(municipioId, turmaId, versaoFila.proxima(), posicoes);
        return posicoes;
    }
}
