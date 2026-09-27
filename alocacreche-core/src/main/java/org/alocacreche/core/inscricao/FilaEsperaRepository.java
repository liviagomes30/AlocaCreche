package org.alocacreche.core.inscricao;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FilaEsperaRepository extends JpaRepository<FilaEspera, Long> {

    List<FilaEspera> findByTurmaIdOrderByPosicaoAsc(Long turmaId);

    Optional<FilaEspera> findByInscricaoId(Long inscricaoId);
}
