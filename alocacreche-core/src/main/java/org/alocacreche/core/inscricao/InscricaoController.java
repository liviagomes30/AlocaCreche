package org.alocacreche.core.inscricao;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inscricoes")
@RequiredArgsConstructor
public class InscricaoController {

    private final InscricaoService inscricaoService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Inscricao criar(@Valid @RequestBody InscricaoDTO dto) {
        return inscricaoService.registrarInscricao(dto);
    }
}