package org.alocacreche.core.matricula.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.matricula.service.CrecheCriadaResponse;
import org.alocacreche.core.matricula.service.CrecheTurmaService;
import org.alocacreche.core.matricula.service.CriarCrecheRequest;
import org.alocacreche.core.matricula.service.CriarTurmaRequest;
import org.alocacreche.core.matricula.service.TurmaCriadaResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/matricula")
@RequiredArgsConstructor
public class CrecheTurmaController {

    private final CrecheTurmaService crecheTurmaService;

    @SaCheckPermission("matricula:creche:add")
    @PostMapping("/creches")
    public ResponseEntity<CrecheCriadaResponse> criarCreche(@Valid @RequestBody CriarCrecheRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(crecheTurmaService.criarCreche(request));
    }

    @SaCheckPermission("matricula:turma:add")
    @PostMapping("/turmas")
    public ResponseEntity<TurmaCriadaResponse> criarTurma(@Valid @RequestBody CriarTurmaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(crecheTurmaService.criarTurma(request));
    }
}
