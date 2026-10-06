package org.alocacreche.core.inscricao;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inscricao/inscricoes")
@RequiredArgsConstructor
public class InscricaoController {

    private final InscricaoService inscricaoService;

    @SaCheckPermission("inscricao:inscricao:add")
    @PostMapping("/por-crianca")
    @ResponseStatus(HttpStatus.CREATED)
    public InscricaoRegistradaResponse criar(@Valid @RequestBody InscricaoDTO dto) {
        return inscricaoService.registrarInscricao(dto);
    }
}
