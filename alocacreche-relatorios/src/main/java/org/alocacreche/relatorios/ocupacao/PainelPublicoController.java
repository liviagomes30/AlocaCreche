package org.alocacreche.relatorios.ocupacao;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Painel público cacheado (ver docs/arquitetura/visao-geral.md, consultarPainelPublico).
 * Autenticação intencionalmente ausente - painel é público por definição (mesma decisão do Epic 1
 * para o relatório gerencial, mas aqui é a escolha natural, não um TODO).
 */
@RestController
@RequestMapping("/api/v1/relatorios/painel-publico")
@RequiredArgsConstructor
public class PainelPublicoController {

    private final PainelPublicoService service;

    @GetMapping
    public PainelPublicoDTO consultar(@RequestParam Long municipioId) {
        return service.obterPainel(municipioId);
    }
}
