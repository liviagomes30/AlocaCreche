package org.alocacreche.relatorios.ocupacao;

import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/relatorios/ocupacao")
@RequiredArgsConstructor
public class OcupacaoConsultaController {

    private final OcupacaoTurmaRepository repository;
    private final EntityManager entityManager;

    @GetMapping
    @Transactional(readOnly = true)
    public List<OcupacaoTurmaDTO> consultar(
            @RequestParam Long municipioId,
            @RequestParam(required = false) Long crecheId,
            @RequestParam(required = false) Long turmaId) {
        TenantContext.aplicar(entityManager, municipioId);

        return repository.buscarPorFiltro(municipioId, crecheId, turmaId).stream()
                .map(
                        turma ->
                                new OcupacaoTurmaDTO(
                                        turma.getTurmaId(),
                                        turma.getCrecheId(),
                                        turma.getMunicipioId(),
                                        turma.getVagasDisponiveis(),
                                        turma.getUltimaSequenciaAplicada(),
                                        turma.getAtualizadoEm()))
                .toList();
    }
}
