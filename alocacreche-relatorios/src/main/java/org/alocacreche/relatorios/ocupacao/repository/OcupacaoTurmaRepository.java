package org.alocacreche.relatorios.ocupacao.repository;

import java.util.List;
import org.alocacreche.relatorios.ocupacao.domain.OcupacaoTurma;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OcupacaoTurmaRepository extends JpaRepository<OcupacaoTurma, Long> {

    @Modifying(clearAutomatically = true)
    @Query(
            value = """
                INSERT INTO ocupacao_turma (turma_id, municipio_id, creche_id, vagas_disponiveis, ultima_sequencia_aplicada, atualizado_em)
                VALUES (:turmaId, :municipioId, :crecheId, :vagasDisponiveis, :eventId, now())
                ON CONFLICT (turma_id) DO UPDATE SET
                    vagas_disponiveis = EXCLUDED.vagas_disponiveis,
                    creche_id = EXCLUDED.creche_id,
                    ultima_sequencia_aplicada = EXCLUDED.ultima_sequencia_aplicada,
                    atualizado_em = now()
                WHERE ocupacao_turma.ultima_sequencia_aplicada < EXCLUDED.ultima_sequencia_aplicada
                """,
            nativeQuery = true)
    void upsert(
            @Param("turmaId") Long turmaId,
            @Param("municipioId") Long municipioId,
            @Param("crecheId") Long crecheId,
            @Param("vagasDisponiveis") Integer vagasDisponiveis,
            @Param("eventId") Long eventId);

    @Query(
            "SELECT o FROM OcupacaoTurma o WHERE o.municipioId = :municipioId "
                    + "AND (:crecheId IS NULL OR o.crecheId = :crecheId) "
                    + "AND (:turmaId IS NULL OR o.turmaId = :turmaId)")
    List<OcupacaoTurma> buscarPorFiltro(
            @Param("municipioId") Long municipioId,
            @Param("crecheId") Long crecheId,
            @Param("turmaId") Long turmaId);
}
