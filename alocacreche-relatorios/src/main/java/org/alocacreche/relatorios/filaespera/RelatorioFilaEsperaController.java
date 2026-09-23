package org.alocacreche.relatorios.filaespera;

import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leitura agregada por turma da projeção de fila de espera (ver docs/arquitetura/visao-geral.md,
 * relatorioFilaEspera). Autenticação intencionalmente ausente por agora - ver spec.md, Out of
 * Scope (mesmo TODO documentado de {@code OcupacaoConsultaController}).
 *
 * <p>{@code municipioId} sem {@code required = false} já produz HTTP 400 automático do Spring MVC
 * quando ausente, antes deste método rodar - nem {@link TenantContext} nem o repositório são
 * tocados nesse caso (FILA-11).
 */
@RestController
@RequestMapping("/api/v1/relatorios/fila-espera")
@RequiredArgsConstructor
public class RelatorioFilaEsperaController {

    private final FilaEsperaInscricaoRepository repository;
    private final EntityManager entityManager;

    @GetMapping
    @Transactional(readOnly = true)
    public List<RelatorioFilaEsperaDTO> consultar(
            @RequestParam Long municipioId,
            @RequestParam(required = false) Long crecheId,
            @RequestParam(required = false) Long turmaId) {
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
