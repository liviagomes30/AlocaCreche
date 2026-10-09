package org.alocacreche.core.comum.seguranca.repository;

import java.util.Optional;
import org.alocacreche.core.comum.seguranca.domain.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);
}
