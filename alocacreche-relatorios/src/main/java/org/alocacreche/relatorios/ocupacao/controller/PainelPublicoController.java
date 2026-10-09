package org.alocacreche.relatorios.ocupacao.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import org.alocacreche.relatorios.ocupacao.dto.PainelPublicoDTO;
import org.alocacreche.relatorios.ocupacao.service.PainelPublicoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/relatorios/painel-publico")
@RequiredArgsConstructor
public class PainelPublicoController {

    private final PainelPublicoService service;

    @GetMapping
    @SaIgnore
    public PainelPublicoDTO consultar(@RequestParam Long municipioId) {
        return service.obterPainel(municipioId);
    }
}
