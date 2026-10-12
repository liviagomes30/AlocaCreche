package org.alocacreche.core.outbox.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import org.alocacreche.core.outbox.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByIdAsc(String status, Pageable pageable);

    long countByStatus(String status);

    /**
     * Pendentes travados com {@code FOR UPDATE SKIP LOCKED} (lock.timeout = -2): com duas instancias
     * do core, cada uma pega eventos diferentes e nenhum evento e publicado em duplicidade. Exige
     * transacao aberta.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.status = :status order by e.id asc")
    List<OutboxEvent> buscarPendentesParaPublicar(@Param("status") String status, Pageable pageable);

    /**
     * O vaga.liberada PROCESSADO mais antigo da turma: serve para a demonstracao reenviar um evento
     * velho e provar que o MSRelatorios o ignora (a sequencia dele e menor que a ja aplicada).
     */
    @Query(
            value = "select * from outbox o where o.tipo_evento = 'vaga.liberada' and o.status = 'PROCESSADO' "
                    + "and cast(jsonb_extract_path_text(o.payload, 'turmaId') as bigint) = :turmaId "
                    + "order by o.id asc limit 1",
            nativeQuery = true)
    java.util.Optional<OutboxEvent> buscarVagaLiberadaMaisAntigaDaTurma(@Param("turmaId") Long turmaId);

    @Modifying
    @Query("delete from OutboxEvent e where e.status = :status and e.criadoEm < :limite")
    int apagarPorStatusCriadosAntes(
            @Param("status") String status, @Param("limite") LocalDateTime limite);
}
