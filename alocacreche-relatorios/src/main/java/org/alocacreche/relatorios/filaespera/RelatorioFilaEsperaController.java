package org.alocacreche.relatorios.filaespera;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.seguranca.MunicipioDoToken;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Relatórios - fila de espera", description = "Quantidade aguardando e tempo médio de espera, escopados por município.")
@RequestMapping("/api/v1/relatorios/fila-espera")
@RequiredArgsConstructor
public class RelatorioFilaEsperaController {

    private final FilaEsperaInscricaoRepository repository;
    private final EntityManager entityManager;
    private final MunicipioDoToken municipioDoToken;

    @GetMapping
    @SaCheckPermission("relatorios:fila:list")
    @Transactional(readOnly = true)
    public List<RelatorioFilaEsperaDTO> consultar(
            @Parameter(description = "Município consultado; precisa ser o mesmo do token.", example = "1") @RequestParam Long municipioId,
            @Parameter(description = "Filtra por creche (opcional).") @RequestParam(required = false) Long crecheId,
            @Parameter(description = "Filtra por turma (opcional).") @RequestParam(required = false) Long turmaId) {
        municipioDoToken.exigir(municipioId);
        TenantContext.aplicar(entityManager, municipioId);

        return repository.buscarRelatorio(municipioId, crecheId, turmaId).stream()
                .map(
                        linha ->
                                new RelatorioFilaEsperaDTO(
                                        linha.getTurmaId(),
                                        linha.getCrecheId(),
                                        linha.getMunicipioId(),
                                        linha.getQuantidadeAguardando(),
                                        linha.getTempoMedioEsperaDias()))
                .toList();
    }
}
