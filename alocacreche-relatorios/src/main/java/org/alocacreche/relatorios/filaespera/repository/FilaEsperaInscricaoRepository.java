package org.alocacreche.relatorios.filaespera.repository;

import java.time.LocalDate;
import java.util.List;
import org.alocacreche.relatorios.filaespera.domain.FilaEsperaInscricao;
import org.alocacreche.relatorios.filaespera.projection.RelatorioFilaEsperaProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FilaEsperaInscricaoRepository extends JpaRepository<FilaEsperaInscricao, Long> {

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
