package org.alocacreche.core.inscricao;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResponsavelRepository extends JpaRepository<Responsavel, Long> {

    Optional<Responsavel> findByCpf(String cpf);
}
