package org.alocacreche.core.inscricao;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/criancas")
@RequiredArgsConstructor
public class CriancaController {

    private final CriancaService criancaService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Crianca cadastrar(@Valid @RequestBody CriancaDTO dto) {
        return criancaService.cadastrar(dto);
    }
}