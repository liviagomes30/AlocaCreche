package org.alocacreche.relatorios.ocupacao;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OcupacaoTurmaRepository extends JpaRepository<OcupacaoTurma, Long> {

    /**
     * Upsert atomico e idempotente: cria a linha se turmaId nao existir; atualiza somente se a
     * sequencia recebida for maior que a ja aplicada. Sem janela de corrida - o Postgres serializa
     * concorrencia no INSERT ... ON CONFLICT (ver OCUP-01/02/03).
     *
     * <p>clearAutomatically = true: um upsert nativo nao atualiza o primeiro nivel de cache do
     * EntityManager - sem isso, um find() na mesma transacao apos este upsert devolveria dado
     * obsoleto. Hoje nenhum consumidor le e escreve a mesma entidade na mesma transacao, mas e uma
     * garantia barata para um consumidor futuro que vier a fazer isso.
     */
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

    /**
     * Consulta com municipioId obrigatorio e crecheId/turmaId opcionais. Um unico metodo em vez de
     * 4 metodos derivados (uma combinacao por filtro binario) - o volume de dados por municipio e
     * pequeno, entao o padrao "IS NULL OR" nao e uma preocupacao de performance aqui. O filtro por
     * municipioId no WHERE e redundante por design com a RLS (defesa em profundidade, ver AD-002) -
     * mesmo que a RLS falhasse por algum motivo, este filtro explicito ainda restringe.
     */
    @Query(
            "SELECT o FROM OcupacaoTurma o WHERE o.municipioId = :municipioId "
                    + "AND (:crecheId IS NULL OR o.crecheId = :crecheId) "
                    + "AND (:turmaId IS NULL OR o.turmaId = :turmaId)")
    List<OcupacaoTurma> buscarPorFiltro(
            @Param("municipioId") Long municipioId,
            @Param("crecheId") Long crecheId,
            @Param("turmaId") Long turmaId);
}
