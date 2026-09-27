package org.alocacreche.core.inscricao;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CriancaRepository extends JpaRepository<Crianca, Long> {

    List<Crianca> findByResponsavelId(Long responsavelId);
}
