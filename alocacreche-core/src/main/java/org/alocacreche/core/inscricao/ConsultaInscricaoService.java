package org.alocacreche.core.inscricao;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.seguranca.service.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listagens simples (somente leitura) para alimentar a interface. CPF, renda e criterio de
 * vulnerabilidade ficam de fora de proposito (LGPD): a tela nao precisa do dado bruto.
 */
@Service
@RequiredArgsConstructor
public class ConsultaInscricaoService {

    public record ResponsavelResumo(Long id, String nome, Double latitude, Double longitude) {}

    public record CriancaResumo(Long id, Long responsavelId, String nome) {}

    public record InscricaoResumo(
            Long id,
            Long criancaId,
            String nome,
            Long turmaId,
            Integer posicao,
            Double scorePrioridade,
            StatusInscricao status,
            LocalDateTime dataInscricao) {}

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final ResponsavelRepository responsavelRepository;
    private final CriancaRepository criancaRepository;
    private final InscricaoRepository inscricaoRepository;
    private final FilaEsperaRepository filaEsperaRepository;

    @Transactional(readOnly = true)
    public List<ResponsavelResumo> listarResponsaveis() {
        aplicarTenantAtual();
        return responsavelRepository.findAll().stream()
                .map(r -> new ResponsavelResumo(
                        r.getId(),
                        r.getNome(),
                        r.getEndereco() == null ? null : r.getEndereco().getLatitude(),
                        r.getEndereco() == null ? null : r.getEndereco().getLongitude()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CriancaResumo> listarCriancas() {
        aplicarTenantAtual();
        return criancaRepository.findAll().stream()
                .map(c -> new CriancaResumo(c.getId(), c.getResponsavel().getId(), c.getNome()))
                .toList();
    }

    /** Turma e posicao vem da fila de espera; quem ja saiu da fila (matriculado) fica com ambos nulos. */
    @Transactional(readOnly = true)
    public List<InscricaoResumo> listarInscricoes() {
        Long municipioId = aplicarTenantAtual();
        Map<Long, FilaEspera> filaPorInscricao = filaEsperaRepository.findAll().stream()
                .collect(Collectors.toMap(f -> f.getInscricao().getId(), f -> f, (a, b) -> a));
        return inscricaoRepository.findByMunicipioId(municipioId).stream()
                .map(i -> {
                    FilaEspera fila = filaPorInscricao.get(i.getId());
                    return new InscricaoResumo(
                            i.getId(),
                            i.getCrianca().getId(),
                            i.getCrianca().getNome(),
                            fila == null ? null : fila.getTurmaId(),
                            fila == null ? null : fila.getPosicao(),
                            i.getScorePrioridade(),
                            i.getStatus(),
                            i.getDataInscricao());
                })
                .toList();
    }

    private Long aplicarTenantAtual() {
        Long municipioId = sessaoTenantResolver.municipioIdAtual();
        TenantContext.aplicar(entityManager, municipioId);
        return municipioId;
    }
}
