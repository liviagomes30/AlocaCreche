package org.alocacreche.core.matricula.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.matricula.service.ConsultaMatriculaService;
import org.alocacreche.core.matricula.service.CrecheCriadaResponse;
import org.alocacreche.core.matricula.service.CrecheProximaResponse;
import org.alocacreche.core.matricula.service.CrecheTurmaService;
import org.alocacreche.core.matricula.service.CriarCrecheRequest;
import org.alocacreche.core.matricula.service.CriarTurmaRequest;
import org.alocacreche.core.matricula.service.SugerirCrechesRequest;
import org.alocacreche.core.matricula.service.SugestaoCrecheService;
import org.alocacreche.core.matricula.service.TurmaCriadaResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Creches e turmas", description = "Cadastro de creches e turmas e sugestão de creches próximas.")
@RequestMapping("/api/v1/matricula")
@RequiredArgsConstructor
public class CrecheTurmaController {

    private final CrecheTurmaService crecheTurmaService;
    private final SugestaoCrecheService sugestaoCrecheService;
    private final ConsultaMatriculaService consultaMatriculaService;

    @SaCheckPermission("matricula:creche:list")
    @GetMapping("/creches")
    public List<ConsultaMatriculaService.CrecheResumo> listarCreches() {
        return consultaMatriculaService.listarCreches();
    }

    @SaCheckPermission("matricula:turma:list")
    @GetMapping("/turmas")
    public List<ConsultaMatriculaService.TurmaResumo> listarTurmas() {
        return consultaMatriculaService.listarTurmas();
    }

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

    @SaCheckPermission("matricula:creche:list")
    @GetMapping("/creches/proximas")
    public List<CrecheProximaResponse> sugerirProximas(@Valid @ModelAttribute SugerirCrechesRequest request) {
        return sugestaoCrecheService.sugerirCrechesProximas(request);
    }
}
