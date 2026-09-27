package org.alocacreche.core.matricula;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrecheRepository extends JpaRepository<Creche, Long> {

    List<Creche> findByMunicipioId(Long municipioId);
}
