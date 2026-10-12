package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.seguranca.service.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.CrecheRepository;
import org.alocacreche.core.matricula.FaixaEtaria;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.alocacreche.core.matricula.TurmaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Listagens simples (somente leitura) de creches, turmas e matriculas para alimentar a interface. */
@Service
@RequiredArgsConstructor
public class ConsultaMatriculaService {

    public record CrecheResumo(Long id, String nome, Integer capacidadeTotal, Double latitude, Double longitude) {}

    public record TurmaResumo(
            Long id, Long crecheId, FaixaEtaria faixaEtaria, Integer vagasTotais, Integer vagasDisponiveis) {}

    public record MatriculaResumo(
            Long id,
            Long inscricaoId,
            Long turmaId,
            StatusMatricula status,
            LocalDateTime prazoConfirmacao,
            Double distanciaKm) {}

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final CrecheRepository crecheRepository;
    private final TurmaRepository turmaRepository;
    private final MatriculaRepository matriculaRepository;

    @Transactional(readOnly = true)
    public List<CrecheResumo> listarCreches() {
        aplicarTenantAtual();
        return crecheRepository.findAll().stream()
                .map(c -> new CrecheResumo(
                        c.getId(),
                        c.getNome(),
                        c.getCapacidadeTotal(),
                        c.getEndereco() == null ? null : c.getEndereco().getLatitude(),
                        c.getEndereco() == null ? null : c.getEndereco().getLongitude()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TurmaResumo> listarTurmas() {
        aplicarTenantAtual();
        return turmaRepository.findAll().stream()
                .map(t -> new TurmaResumo(
                        t.getId(),
                        t.getCreche().getId(),
                        t.getFaixaEtaria(),
                        t.getVagasTotais(),
                        t.getVagasDisponiveis()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MatriculaResumo> listarMatriculas() {
        aplicarTenantAtual();
        return matriculaRepository.findAll().stream()
                .map(m -> new MatriculaResumo(
                        m.getId(),
                        m.getInscricaoId(),
                        m.getTurma().getId(),
                        m.getStatus(),
                        m.getPrazoConfirmacao(),
                        m.getDistanciaKm()))
                .toList();
    }

    private void aplicarTenantAtual() {
        TenantContext.aplicar(entityManager, sessaoTenantResolver.municipioIdAtual());
    }
}
