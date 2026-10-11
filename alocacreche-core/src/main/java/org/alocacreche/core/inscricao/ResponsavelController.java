package org.alocacreche.core.inscricao;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Responsáveis", description = "Cadastro de responsáveis (CPF é dado sensível: só dados fictícios nos testes).")
@RequestMapping("/api/v1/inscricao/responsaveis")
@RequiredArgsConstructor
public class ResponsavelController {

    private final ResponsavelService responsavelService;

    @SaCheckPermission("inscricao:responsavel:add")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponsavelCadastradoResponse cadastrar(@Valid @RequestBody ResponsavelDTO dto) {
        return new ResponsavelCadastradoResponse(responsavelService.cadastrar(dto).getId());
    }
}
