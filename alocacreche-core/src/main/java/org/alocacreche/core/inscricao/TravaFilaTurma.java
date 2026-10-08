package org.alocacreche.core.inscricao;

import jakarta.persistence.EntityManager;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializa toda escrita na fila de espera de uma mesma turma (enfileiramento e alocacao) com um
 * advisory lock do Postgres escopado a transacao ({@code pg_advisory_xact_lock}).
 *
 * <p>Por que advisory lock e nao {@code PESSIMISTIC_WRITE} na Turma: o lock de linha da Turma
 * conflita com o {@code @Version} (lock otimista) usado pela alocacao e pela confirmacao de
 * matricula, e faria toda inscricao disputar a mesma linha que o fluxo de vagas. O advisory lock
 * nao toca nenhuma linha: solta sozinho no commit ou rollback e so bloqueia quem tambem o pede.
 *
 * <p>Forma de duas chaves inteiras: o primeiro inteiro e um namespace constante da fila (evita
 * colisao com outros usos de advisory lock) e o segundo e derivado do id da turma. O id da turma
 * ja e unico entre municipios (chave primaria), entao o municipio nao precisa entrar na chave;
 * colisao de hash entre duas turmas apenas serializa as duas sem afetar a corretude.
 *
 * <p>Exige transacao ativa ({@code MANDATORY}): fora dela o lock seria solto na hora e nao
 * protegeria nada. Deve ser chamado depois de {@code TenantContext.aplicar} e antes de ler ou
 * gravar a fila da turma.
 */
@Component
@RequiredArgsConstructor
public class TravaFilaTurma {

    static final int NAMESPACE_FILA = 0x46494C41;

    static final String COMANDO_TRAVAR = "select pg_advisory_xact_lock(?1, ?2)";

    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void travar(Long municipioId, Long turmaId) {
        Objects.requireNonNull(municipioId, "municipioId nao pode ser nulo");
        Objects.requireNonNull(turmaId, "turmaId nao pode ser nulo");
        entityManager
                .createNativeQuery(COMANDO_TRAVAR)
                .setParameter(1, NAMESPACE_FILA)
                .setParameter(2, chaveDaTurma(turmaId))
                .getResultList();
    }

    static int chaveDaTurma(Long turmaId) {
        return Long.hashCode(turmaId);
    }
}
