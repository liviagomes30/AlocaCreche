package org.alocacreche.core.inscricao;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/responsaveis")
@RequiredArgsConstructor
public class ResponsavelController {

    private final ResponsavelService responsavelService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Responsavel cadastrar(@Valid @RequestBody ResponsavelDTO dto) {
        return responsavelService.cadastrar(dto);
    }
}