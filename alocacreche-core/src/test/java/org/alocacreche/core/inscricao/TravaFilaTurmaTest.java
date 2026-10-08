package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
 * Prova o contrato do lock por turma: advisory lock de transacao com as duas chaves (namespace
 * fixo e id da turma) e obrigatoriedade de transacao ativa. O efeito de serializar escritas
 * concorrentes com Postgres real esta em InscricaoFilaConcorrenciaIT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TravaFilaTurma")
class TravaFilaTurmaTest {

    private static final Long MUNICIPIO_ID = 7L;
    private static final Long TURMA_ID = 10L;

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    @Test
    @DisplayName("executa pg_advisory_xact_lock com o namespace da fila e a chave derivada da turma")
    void executaOAdvisoryLockComAsDuasChaves() throws Exception {
        when(entityManager.createNativeQuery("select pg_advisory_xact_lock(?1, ?2)")).thenReturn(query);
        when(query.setParameter(any(Integer.class), any())).thenReturn(query);

        new TravaFilaTurma(entityManager).travar(MUNICIPIO_ID, TURMA_ID);

        verify(query).setParameter(1, TravaFilaTurma.NAMESPACE_FILA);
        verify(query).setParameter(2, TravaFilaTurma.chaveDaTurma(TURMA_ID));
        verify(query).getResultList();
    }

    @Test
    @DisplayName("turmas diferentes geram chaves diferentes")
    void turmasDiferentesGeramChavesDiferentes() {
        assertThat(TravaFilaTurma.chaveDaTurma(10L)).isNotEqualTo(TravaFilaTurma.chaveDaTurma(11L));
    }

    @Test
    @DisplayName("exige transacao ativa (propagacao MANDATORY)")
    void exigeTransacaoAtiva() throws Exception {
        Transactional transacional =
                TravaFilaTurma.class.getMethod("travar", Long.class, Long.class).getAnnotation(Transactional.class);

        assertThat(transacional).isNotNull();
        assertThat(transacional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("ids nulos sao recusados sem tocar no banco")
    void idsNulosSaoRecusados() {
        TravaFilaTurma trava = new TravaFilaTurma(entityManager);

        assertThatThrownBy(() -> trava.travar(null, TURMA_ID)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> trava.travar(MUNICIPIO_ID, null)).isInstanceOf(NullPointerException.class);

        verifyNoInteractions(entityManager);
    }
}
