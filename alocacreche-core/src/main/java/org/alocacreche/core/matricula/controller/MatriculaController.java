package org.alocacreche.core.matricula.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.alocacreche.core.matricula.service.AlocacaoVagaService;
import org.alocacreche.core.matricula.service.ConsultaMatriculaService;
import org.alocacreche.core.matricula.service.AlocarVagaRequest;
import org.alocacreche.core.matricula.service.ConfirmacaoMatriculaService;
import org.alocacreche.core.matricula.service.DesistenciaMatriculaService;
import org.alocacreche.core.matricula.service.MatriculaAlocadaResponse;
import org.alocacreche.core.matricula.service.MatriculaCanceladaResponse;
import org.alocacreche.core.matricula.service.MatriculaConfirmadaResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Matrícula", description = "Alocação de vaga, confirmação e desistência de matrícula.")
@RequestMapping("/api/v1/matricula")
@RequiredArgsConstructor
public class MatriculaController {

    private final AlocacaoVagaService alocacaoVagaService;
    private final ConfirmacaoMatriculaService confirmacaoMatriculaService;
    private final DesistenciaMatriculaService desistenciaMatriculaService;
    private final ConsultaMatriculaService consultaMatriculaService;

    @SaCheckPermission("matricula:matricula:list")
    @GetMapping("/matriculas")
    public List<ConsultaMatriculaService.MatriculaResumo> listar() {
        return consultaMatriculaService.listarMatriculas();
    }

    @SaCheckPermission("matricula:vaga:alocar")
    @PostMapping("/vagas/alocar")
    public ResponseEntity<MatriculaAlocadaResponse> alocar(@Valid @RequestBody AlocarVagaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(alocacaoVagaService.alocarProximoElegivel(request.turmaId()));
    }

    @SaCheckPermission("matricula:matricula:confirmar")
    @PostMapping("/matriculas/{id}/confirmar")
    public ResponseEntity<MatriculaConfirmadaResponse> confirmar(@PathVariable("id") Long matriculaId) {
        return ResponseEntity.ok(confirmacaoMatriculaService.confirmar(matriculaId));
    }

    @SaCheckPermission("matricula:matricula:status")
    @PostMapping("/matriculas/{id}/desistir")
    public ResponseEntity<MatriculaCanceladaResponse> desistir(@PathVariable("id") Long matriculaId) {
        return ResponseEntity.ok(desistenciaMatriculaService.desistir(matriculaId));
    }
}
