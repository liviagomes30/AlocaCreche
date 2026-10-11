package org.alocacreche.relatorios.ocupacao;

import cn.dev33.satoken.annotation.SaIgnore;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Painel público", description = "Ocupação por município, aberta a qualquer pessoa (sem token).")
@RequestMapping("/api/v1/relatorios/painel-publico")
@RequiredArgsConstructor
public class PainelPublicoController {

    private final PainelPublicoService service;

    @GetMapping
    @SaIgnore
    public PainelPublicoDTO consultar(@Parameter(description = "Município do painel.", example = "1") @RequestParam Long municipioId) {
        return service.obterPainel(municipioId);
    }
}
