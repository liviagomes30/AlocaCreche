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
                INSERT INTO ocupacao_turma (turma_id, municipio_id, creche_id, vagas_disponiveis, faixa_etaria, creche_nome, bairro, ultima_sequencia_aplicada, atualizado_em)
                VALUES (:turmaId, :municipioId, :crecheId, :vagasDisponiveis, :faixaEtaria, :crecheNome, :bairro, :eventId, now())
                ON CONFLICT (turma_id) DO UPDATE SET
                    vagas_disponiveis = EXCLUDED.vagas_disponiveis,
                    creche_id = EXCLUDED.creche_id,
                    faixa_etaria = COALESCE(EXCLUDED.faixa_etaria, ocupacao_turma.faixa_etaria),
                    creche_nome = COALESCE(EXCLUDED.creche_nome, ocupacao_turma.creche_nome),
                    bairro = COALESCE(EXCLUDED.bairro, ocupacao_turma.bairro),
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
            @Param("faixaEtaria") String faixaEtaria,
            @Param("crecheNome") String crecheNome,
            @Param("bairro") String bairro,
            @Param("eventId") Long eventId);

    /** Atalho para eventos sem os dados publicos da creche: preserva o que a linha ja tinha. */
    default void upsert(Long turmaId, Long municipioId, Long crecheId, Integer vagasDisponiveis, Long eventId) {
        upsert(turmaId, municipioId, crecheId, vagasDisponiveis, null, null, null, eventId);
    }

    @Query(
            "SELECT o FROM OcupacaoTurma o WHERE o.municipioId = :municipioId "
                    + "AND (:crecheId IS NULL OR o.crecheId = :crecheId) "
                    + "AND (:turmaId IS NULL OR o.turmaId = :turmaId)")
    List<OcupacaoTurma> buscarPorFiltro(
            @Param("municipioId") Long municipioId,
            @Param("crecheId") Long crecheId,
            @Param("turmaId") Long turmaId);
}
