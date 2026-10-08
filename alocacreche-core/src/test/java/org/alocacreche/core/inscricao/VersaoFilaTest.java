package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contrato da versao dos snapshots da fila: le o proximo valor da sequence e exige transacao ativa
 * (a versao so e monotonica em relacao aos commits se pedida sob o lock da turma, dentro da
 * transacao). A ordem das versoes com Postgres real esta em InscricaoFilaRedisIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VersaoFila")
class VersaoFilaTest {

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    @Test
    @DisplayName("devolve o proximo valor da sequence fila_versao_seq")
    void devolveOProximoValorDaSequence() {
        when(entityManager.createNativeQuery("select nextval('fila_versao_seq')")).thenReturn(query);
        when(query.getSingleResult()).thenReturn(41L);

        assertThat(new VersaoFila(entityManager).proxima()).isEqualTo(41L);
    }

    @Test
    @DisplayName("exige transacao ativa (propagacao MANDATORY)")
    void exigeTransacaoAtiva() throws Exception {
        Transactional transacional =
                VersaoFila.class.getMethod("proxima").getAnnotation(Transactional.class);

        assertThat(transacional).isNotNull();
        assertThat(transacional.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
