package org.alocacreche.core.inscricao;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inscricao/inscricoes")
@RequiredArgsConstructor
public class InscricaoController {

    private final InscricaoService inscricaoService;

    @SaCheckPermission("inscricao:inscricao:add")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InscricaoRegistradaResponse criar(@Valid @RequestBody InscricaoDTO dto) {
        return inscricaoService.registrarInscricao(dto);
    }

    @SaCheckPermission("inscricao:fila:detail")
    @GetMapping("/{inscricaoId}/posicao")
    public PosicaoFilaResponse consultarPosicao(
            @PathVariable Long inscricaoId,
            @RequestParam Long turmaId) {

        return inscricaoService.consultarPosicaoNaFila(turmaId, inscricaoId);
    }
}
