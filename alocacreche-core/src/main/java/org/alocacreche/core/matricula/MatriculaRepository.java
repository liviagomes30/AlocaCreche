package org.alocacreche.core.matricula;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatriculaRepository extends JpaRepository<Matricula, Long> {

    List<Matricula> findByTurmaId(Long turmaId);

    Optional<Matricula> findByInscricaoId(Long inscricaoId);
    
    boolean existsByInscricaoId(Long inscricaoId);
}
