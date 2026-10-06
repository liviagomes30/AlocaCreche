package org.alocacreche.core.matricula;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatriculaRepository extends JpaRepository<Matricula, Long> {

    List<Matricula> findByTurmaId(Long turmaId);

    Optional<Matricula> findByInscricaoId(Long inscricaoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Matricula m where m.id = :id")
    Optional<Matricula> buscarParaConfirmar(@Param("id") Long id);
}
