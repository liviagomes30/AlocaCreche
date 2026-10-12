package org.alocacreche.core.inscricao;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Crianças", description = "Cadastro de crianças vinculadas a um responsável.")
@RequestMapping("/api/v1/inscricao/criancas")
@RequiredArgsConstructor
public class CriancaController {

    private final CriancaService criancaService;
    private final ConsultaInscricaoService consultaInscricaoService;

    @SaCheckPermission("inscricao:crianca:list")
    @GetMapping
    public List<ConsultaInscricaoService.CriancaResumo> listar() {
        return consultaInscricaoService.listarCriancas();
    }

    @SaCheckPermission("inscricao:crianca:add")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CriancaCadastradaResponse cadastrar(@Valid @RequestBody CadastroCriancaRequest requisicao) {
        return new CriancaCadastradaResponse(
                criancaService.cadastrar(requisicao.responsavelId(), requisicao.dados()).getId());
    }
}
