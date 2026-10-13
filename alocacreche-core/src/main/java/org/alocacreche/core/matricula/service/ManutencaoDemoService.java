package org.alocacreche.core.matricula.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.comum.excecao.ConflitoException;
import org.alocacreche.core.comum.excecao.RecursoNaoEncontradoException;
import org.alocacreche.core.comum.seguranca.service.SessaoTenantResolver;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.Matricula;
import org.alocacreche.core.matricula.MatriculaRepository;
import org.alocacreche.core.matricula.StatusMatricula;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apoio a demonstracao do job de manutencao, sem esperar o relogio nem ligar o job agendado (que aloca vagas
 * sozinho e atrapalha os outros cenarios): vence o prazo de uma matricula PENDENTE e executa uma rodada do job
 * sob demanda, com a mesma logica do agendador. So e exposto com o controle de demonstracao ligado.
 */
@Service
@RequiredArgsConstructor
public class ManutencaoDemoService {

    /**
     * Data fixa no passado de proposito: o prazo e gravado no fuso da JVM e o now() do Postgres pode estar em
     * outro fuso, o que faria um "prazo de ontem" nunca vencer.
     */
    static final LocalDateTime PRAZO_VENCIDO = LocalDateTime.of(2000, 1, 1, 0, 0);

    public record PrazoVencidoResponse(Long matriculaId, LocalDateTime prazoConfirmacao) {}

    private final SessaoTenantResolver sessaoTenantResolver;
    private final EntityManager entityManager;
    private final MatriculaRepository matriculaRepository;
    private final ManutencaoMatriculaPorMunicipio manutencaoMatriculaPorMunicipio;

    @Transactional
    public PrazoVencidoResponse vencerPrazo(Long matriculaId) {
        TenantContext.aplicar(entityManager, sessaoTenantResolver.municipioIdAtual());

        Matricula matricula =
                matriculaRepository
                        .findById(matriculaId)
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Matricula nao encontrada"));
        if (matricula.getStatus() != StatusMatricula.PENDENTE) {
            throw new ConflitoException("So uma matricula PENDENTE pode ter o prazo vencido");
        }
        matricula.setPrazoConfirmacao(PRAZO_VENCIDO);
        matriculaRepository.saveAndFlush(matricula);
        return new PrazoVencidoResponse(matricula.getId(), matricula.getPrazoConfirmacao());
    }

    /** Sem transacao propria, como o agendador: cada passo do job abre a sua com o tenant do municipio. */
    public void executarRodadaDoJob() {
        manutencaoMatriculaPorMunicipio.executar(sessaoTenantResolver.municipioIdAtual());
    }
}
