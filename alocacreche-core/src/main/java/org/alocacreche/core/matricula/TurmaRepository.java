package org.alocacreche.core.matricula;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TurmaRepository extends JpaRepository<Turma, Long> {

    List<Turma> findByCrecheId(Long crecheId);

    /**
     * Trava a linha da turma ate o fim da transacao para serializar quem entra na fila dela - duas
     * inscricoes simultaneas na mesma turma nao leem a mesma maior posicao. PESSIMISTIC_WRITE nao
     * incrementa {@code version}, entao nao interfere no lock otimista da alocacao.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Turma t where t.id = :id")
    Optional<Turma> buscarParaEnfileirar(@Param("id") Long id);

    @Query(
            "select t.id from Turma t where t.vagasDisponiveis > 0"
                    + " and exists (select 1 from FilaEspera f where f.turmaId = t.id) order by t.id")
    List<Long> buscarIdsComVagaEFila();

    @Query("select t from Turma t join fetch t.creche where t.faixaEtaria = :faixaEtaria")
    List<Turma> buscarPorFaixaEtariaComCreche(@Param("faixaEtaria") FaixaEtaria faixaEtaria);
}
