package org.alocacreche.core.matricula;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TurmaRepository extends JpaRepository<Turma, Long> {

    List<Turma> findByCrecheId(Long crecheId);
}
