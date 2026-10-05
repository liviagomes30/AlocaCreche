package org.alocacreche.core.inscricao;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FilaEsperaRepository extends JpaRepository<FilaEspera, Long> {

    List<FilaEspera> findByTurmaIdOrderByPosicaoAsc(Long turmaId);

    Optional<FilaEspera> findByInscricaoId(Long inscricaoId);

    @Query("select max(f.posicao) from FilaEspera f where f.turmaId = :turmaId")
    Optional<Integer> findMaiorPosicaoByTurmaId(@Param("turmaId") Long turmaId);
}
