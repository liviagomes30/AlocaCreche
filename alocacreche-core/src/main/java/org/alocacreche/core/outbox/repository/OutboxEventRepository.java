package org.alocacreche.core.outbox.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByIdAsc(String status, Pageable pageable);

    @Modifying
    @Query("delete from OutboxEvent e where e.status = :status and e.criadoEm < :limite")
    int apagarPorStatusCriadosAntes(
            @Param("status") String status, @Param("limite") LocalDateTime limite);
}
