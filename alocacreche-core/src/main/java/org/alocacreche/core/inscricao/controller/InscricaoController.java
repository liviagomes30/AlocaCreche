package org.alocacreche.core.inscricao.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.inscricao.service.InscricaoCriadaResponse;
import org.alocacreche.core.inscricao.service.InscricaoService;
import org.alocacreche.core.inscricao.service.RegistrarInscricaoRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("inscricaoApiController")
@RequestMapping("/api/v1/inscricao")
@RequiredArgsConstructor
public class InscricaoController {

    private final InscricaoService inscricaoService;

    @SaCheckPermission("inscricao:inscricao:add")
    @PostMapping("/inscricoes")
    public ResponseEntity<InscricaoCriadaResponse> registrar(
            @Valid @RequestBody RegistrarInscricaoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inscricaoService.registrarInscricao(request));
    }
}
