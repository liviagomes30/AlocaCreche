package org.alocacreche.relatorios.filaespera;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FilaEsperaInscricaoRepository extends JpaRepository<FilaEsperaInscricao, Long> {

    /**
     * Upsert atomico e idempotente: cria a linha se inscricaoId nao existir; atualiza somente se a
     * sequencia recebida for maior que a ja aplicada. Sem janela de corrida - o Postgres serializa
     * concorrencia no INSERT ... ON CONFLICT (ver FILA-01/02/03).
     *
     * <p>clearAutomatically = true: mesma razao de OcupacaoTurmaRepository.upsert - um upsert nativo
     * nao atualiza o primeiro nivel de cache do EntityManager.
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value = """
                INSERT INTO fila_espera_inscricao (inscricao_id, turma_id, creche_id, municipio_id, data_entrada, data_saida, ultima_sequencia_aplicada, atualizado_em)
                VALUES (:inscricaoId, :turmaId, :crecheId, :municipioId, :dataEntrada, :dataSaida, :eventId, now())
                ON CONFLICT (inscricao_id) DO UPDATE SET
                    turma_id = EXCLUDED.turma_id,
                    creche_id = EXCLUDED.creche_id,
                    data_entrada = EXCLUDED.data_entrada,
                    data_saida = EXCLUDED.data_saida,
                    ultima_sequencia_aplicada = EXCLUDED.ultima_sequencia_aplicada,
                    atualizado_em = now()
                WHERE fila_espera_inscricao.ultima_sequencia_aplicada < EXCLUDED.ultima_sequencia_aplicada
                """,
            nativeQuery = true)
    void upsert(
            @Param("inscricaoId") Long inscricaoId,
            @Param("turmaId") Long turmaId,
            @Param("crecheId") Long crecheId,
            @Param("municipioId") Long municipioId,
            @Param("dataEntrada") LocalDate dataEntrada,
            @Param("dataSaida") LocalDate dataSaida,
            @Param("eventId") Long eventId);

    /**
     * Agrega por turma em tempo de consulta - contagem de quem ainda aguarda (dataSaida nula) e o
     * tempo medio de espera em dias, incluindo quem ainda aguarda no calculo (COALESCE com
     * CURRENT_DATE). Turma sem nenhuma linha simplesmente nao aparece no GROUP BY (FILA-15). Mesmo
     * padrao "(:crecheId IS NULL OR ...)" de OcupacaoTurmaRepository.buscarPorFiltro - volume pequeno
     * por municipio torna o custo aceitavel.
     */
    @Query(
            value = """
                SELECT
                    turma_id AS turmaId,
                    creche_id AS crecheId,
                    municipio_id AS municipioId,
                    COUNT(*) FILTER (WHERE data_saida IS NULL) AS quantidadeAguardando,
                    AVG(COALESCE(data_saida, CURRENT_DATE) - data_entrada)::float8 AS tempoMedioEsperaDias
                FROM fila_espera_inscricao
                WHERE municipio_id = :municipioId
                  AND (:crecheId IS NULL OR creche_id = :crecheId)
                  AND (:turmaId IS NULL OR turma_id = :turmaId)
                GROUP BY turma_id, creche_id, municipio_id
                """,
            nativeQuery = true)
    List<RelatorioFilaEsperaProjection> buscarRelatorio(
            @Param("municipioId") Long municipioId,
            @Param("crecheId") Long crecheId,
            @Param("turmaId") Long turmaId);
}
