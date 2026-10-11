package org.alocacreche.relatorios.matricula;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.seguranca.MunicipioDoToken;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Relatórios - matrícula", description = "Distância média de alocação e taxa de confirmação por período.")
@RequestMapping("/api/v1/relatorios/matricula")
@RequiredArgsConstructor
public class RelatorioMatriculaController {

    private final MatriculaRelatorioRepository repository;
    private final EntityManager entityManager;
    private final MunicipioDoToken municipioDoToken;

    @GetMapping("/distancia-media")
    @SaCheckPermission("relatorios:matricula:list")
    @Transactional(readOnly = true)
    public DistanciaMediaAlocacaoDTO distanciaMedia(
            @Parameter(description = "Município consultado; precisa ser o mesmo do token.", example = "1") @RequestParam Long municipioId,
            @Parameter(description = "Data de referência do período, no formato AAAA-MM-DD.", example = "2026-10-01") @RequestParam LocalDate periodo) {
        municipioDoToken.exigir(municipioId);
        TenantContext.aplicar(entityManager, municipioId);

        Double distanciaMediaKm = repository.calcularDistanciaMedia(municipioId, periodo);
        return new DistanciaMediaAlocacaoDTO(municipioId, periodo, distanciaMediaKm);
    }

    @GetMapping("/taxa-confirmacao")
    @SaCheckPermission("relatorios:matricula:list")
    @Transactional(readOnly = true)
    public RelatorioConfirmacaoMatriculaDTO taxaConfirmacao(
            @Parameter(description = "Município consultado; precisa ser o mesmo do token.", example = "1") @RequestParam Long municipioId,
            @Parameter(description = "Data de referência do período, no formato AAAA-MM-DD.", example = "2026-10-01") @RequestParam LocalDate periodo) {
        municipioDoToken.exigir(municipioId);
        TenantContext.aplicar(entityManager, municipioId);

        RelatorioConfirmacaoProjection projecao = repository.calcularTaxaConfirmacao(municipioId, periodo);
        long total = projecao.getTotalMatriculas();
        long confirmadas = projecao.getTotalConfirmadas();
        Double taxaConfirmacao = total == 0 ? null : (double) confirmadas / total;

        return new RelatorioConfirmacaoMatriculaDTO(municipioId, periodo, total, confirmadas, taxaConfirmacao);
    }
}
