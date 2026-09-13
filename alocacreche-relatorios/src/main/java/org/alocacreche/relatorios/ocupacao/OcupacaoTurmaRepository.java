package org.alocacreche.relatorios.ocupacao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OcupacaoTurmaRepository extends JpaRepository<OcupacaoTurma, Long> {

    /**
     * Upsert atomico e idempotente: cria a linha se turmaId nao existir; atualiza somente se a
     * sequencia recebida for maior que a ja aplicada. Sem janela de corrida - o Postgres serializa
     * concorrencia no INSERT ... ON CONFLICT (ver OCUP-01/02/03).
     */
    @Modifying
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
}
