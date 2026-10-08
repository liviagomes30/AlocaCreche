package org.alocacreche.core.matricula;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TurmaRepository extends JpaRepository<Turma, Long> {

    List<Turma> findByCrecheId(Long crecheId);

    @Query(
            "select t.id from Turma t where t.vagasDisponiveis > 0"
                    + " and exists (select 1 from FilaEspera f where f.turmaId = t.id) order by t.id")
    List<Long> buscarIdsComVagaEFila();

    @Query("select t from Turma t join fetch t.creche where t.faixaEtaria = :faixaEtaria")
    List<Turma> buscarPorFaixaEtariaComCreche(@Param("faixaEtaria") FaixaEtaria faixaEtaria);
}
