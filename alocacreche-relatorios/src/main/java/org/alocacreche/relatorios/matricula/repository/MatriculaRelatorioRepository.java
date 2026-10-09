package org.alocacreche.relatorios.matricula.repository;

import java.time.LocalDate;
import org.alocacreche.relatorios.matricula.domain.MatriculaRelatorio;
import org.alocacreche.relatorios.matricula.projection.RelatorioConfirmacaoProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatriculaRelatorioRepository extends JpaRepository<MatriculaRelatorio, Long> {

    @Modifying(clearAutomatically = true)
    @Query(
            value = """
                INSERT INTO matricula_relatorio (matricula_id, inscricao_id, turma_id, creche_id, municipio_id, status, distancia_km, data_convocacao, data_confirmacao, ultima_sequencia_aplicada, atualizado_em)
                VALUES (:matriculaId, :inscricaoId, :turmaId, :crecheId, :municipioId, :status, :distanciaKm, :dataConvocacao, :dataConfirmacao, :eventId, now())
                ON CONFLICT (matricula_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    distancia_km = EXCLUDED.distancia_km,
                    data_convocacao = EXCLUDED.data_convocacao,
                    data_confirmacao = EXCLUDED.data_confirmacao,
                    ultima_sequencia_aplicada = EXCLUDED.ultima_sequencia_aplicada,
                    atualizado_em = now()
                WHERE matricula_relatorio.ultima_sequencia_aplicada < EXCLUDED.ultima_sequencia_aplicada
                """,
            nativeQuery = true)
    void upsert(
            @Param("matriculaId") Long matriculaId,
            @Param("inscricaoId") Long inscricaoId,
            @Param("turmaId") Long turmaId,
            @Param("crecheId") Long crecheId,
            @Param("municipioId") Long municipioId,
            @Param("status") String status,
            @Param("distanciaKm") Double distanciaKm,
            @Param("dataConvocacao") LocalDate dataConvocacao,
            @Param("dataConfirmacao") LocalDate dataConfirmacao,
            @Param("eventId") Long eventId);

    @Query(
            value = """
                SELECT AVG(distancia_km)
                FROM matricula_relatorio
                WHERE municipio_id = :municipioId
                  AND date_trunc('month', data_convocacao) = date_trunc('month', CAST(:periodo AS date))
                """,
            nativeQuery = true)
    Double calcularDistanciaMedia(@Param("municipioId") Long municipioId, @Param("periodo") LocalDate periodo);

    @Query(
            value = """
                SELECT
                    COUNT(*) AS totalMatriculas,
                    COUNT(*) FILTER (WHERE status = 'CONFIRMADA') AS totalConfirmadas
                FROM matricula_relatorio
                WHERE municipio_id = :municipioId
                  AND date_trunc('month', data_convocacao) = date_trunc('month', CAST(:periodo AS date))
                """,
            nativeQuery = true)
    RelatorioConfirmacaoProjection calcularTaxaConfirmacao(
            @Param("municipioId") Long municipioId, @Param("periodo") LocalDate periodo);
}
