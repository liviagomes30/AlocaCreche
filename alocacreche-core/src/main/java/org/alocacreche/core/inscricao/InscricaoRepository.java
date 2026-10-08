package org.alocacreche.core.inscricao;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InscricaoRepository extends JpaRepository<Inscricao, Long> {

    List<Inscricao> findByMunicipioId(Long municipioId);

    List<Inscricao> findByCriancaId(Long criancaId);

    boolean existsByCriancaIdAndStatusIn(Long criancaId, Collection<StatusInscricao> status);
}
