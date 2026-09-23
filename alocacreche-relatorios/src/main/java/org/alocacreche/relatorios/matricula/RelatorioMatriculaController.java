package org.alocacreche.relatorios.matricula;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leitura dos relatorios de distancia media de alocacao e taxa de confirmacao de matricula, ambos a
 * partir da mesma projecao MatriculaRelatorio (ver docs/arquitetura/visao-geral.md,
 * distanciaMediaAlocacao/taxaConfirmacaoMatricula). Autenticacao (Sa-Token) intencionalmente ausente
 * por agora - mesmo TODO documentado de OcupacaoConsultaController/RelatorioFilaEsperaController.
 *
 * <p>municipioId e periodo sem {@code required = false} ja produzem HTTP 400 automatico do Spring
 * MVC quando ausentes, antes destes metodos rodarem.
 */
@RestController
@RequestMapping("/api/v1/relatorios/matricula")
@RequiredArgsConstructor
public class RelatorioMatriculaController {

    private final MatriculaRelatorioRepository repository;
    private final EntityManager entityManager;

    @GetMapping("/distancia-media")
    @Transactional(readOnly = true)
    public DistanciaMediaAlocacaoDTO distanciaMedia(
            @RequestParam Long municipioId, @RequestParam LocalDate periodo) {
        TenantContext.aplicar(entityManager, municipioId);

        Double distanciaMediaKm = repository.calcularDistanciaMedia(municipioId, periodo);
        return new DistanciaMediaAlocacaoDTO(municipioId, periodo, distanciaMediaKm);
    }

    @GetMapping("/taxa-confirmacao")
    @Transactional(readOnly = true)
    public RelatorioConfirmacaoMatriculaDTO taxaConfirmacao(
            @RequestParam Long municipioId, @RequestParam LocalDate periodo) {
        TenantContext.aplicar(entityManager, municipioId);

        RelatorioConfirmacaoProjection projecao = repository.calcularTaxaConfirmacao(municipioId, periodo);
        long total = projecao.getTotalMatriculas();
        long confirmadas = projecao.getTotalConfirmadas();
        Double taxaConfirmacao = total == 0 ? null : (double) confirmadas / total;

        return new RelatorioConfirmacaoMatriculaDTO(municipioId, periodo, total, confirmadas, taxaConfirmacao);
    }
}
