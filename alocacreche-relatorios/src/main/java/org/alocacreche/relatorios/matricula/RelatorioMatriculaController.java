package org.alocacreche.relatorios.matricula;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
            @RequestParam Long municipioId, @RequestParam LocalDate periodo) {
        municipioDoToken.exigir(municipioId);
        TenantContext.aplicar(entityManager, municipioId);

        Double distanciaMediaKm = repository.calcularDistanciaMedia(municipioId, periodo);
        return new DistanciaMediaAlocacaoDTO(municipioId, periodo, distanciaMediaKm);
    }

    @GetMapping("/taxa-confirmacao")
    @SaCheckPermission("relatorios:matricula:list")
    @Transactional(readOnly = true)
    public RelatorioConfirmacaoMatriculaDTO taxaConfirmacao(
            @RequestParam Long municipioId, @RequestParam LocalDate periodo) {
        municipioDoToken.exigir(municipioId);
        TenantContext.aplicar(entityManager, municipioId);

        RelatorioConfirmacaoProjection projecao = repository.calcularTaxaConfirmacao(municipioId, periodo);
        long total = projecao.getTotalMatriculas();
        long confirmadas = projecao.getTotalConfirmadas();
        Double taxaConfirmacao = total == 0 ? null : (double) confirmadas / total;

        return new RelatorioConfirmacaoMatriculaDTO(municipioId, periodo, total, confirmadas, taxaConfirmacao);
    }
}
