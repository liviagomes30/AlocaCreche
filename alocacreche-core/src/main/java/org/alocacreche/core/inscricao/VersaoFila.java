package org.alocacreche.core.inscricao;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fornece a versao monotonica dos snapshots da fila publicados no Redis, a partir da sequence
 * {@code fila_versao_seq} (criada em {@code schema.sql}).
 *
 * <p>A versao precisa ser pedida dentro da transacao e depois de {@link TravaFilaTurma#travar}: o
 * lock da turma so e solto no commit, entao a ordem das versoes de uma mesma turma e a ordem dos
 * commits. Com isso {@link FilaRedisService} descarta, por versao, um snapshot que chegue atrasado
 * ao Redis depois de um mais novo. A sequence nunca volta atras; rollback apenas deixa um buraco
 * na numeracao, sem efeito no descarte por "versao maior".
 */
@Component
@RequiredArgsConstructor
public class VersaoFila {

    static final String COMANDO_PROXIMA_VERSAO = "select nextval('fila_versao_seq')";

    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public long proxima() {
        Number versao = (Number) entityManager.createNativeQuery(COMANDO_PROXIMA_VERSAO).getSingleResult();
        return versao.longValue();
    }
}
