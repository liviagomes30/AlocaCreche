package org.alocacreche.core.notificacao;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificacaoRepository extends JpaRepository<Notificacao, Long> {

    List<Notificacao> findByInscricaoId(Long inscricaoId);
}
