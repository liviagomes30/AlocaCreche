package org.alocacreche.core.inscricao;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
@Tag(name = "Inscrição", description = "Inscrição na fila de espera de uma turma e consulta da posição na fila.")
@RequestMapping("/api/v1/inscricao/inscricoes")
@RequiredArgsConstructor
public class InscricaoController {

    private static final String EXEMPLO_CRIANCA_JA_CADASTRADA = """
            {
              "criancaId": 10,
              "turmaId": 1,
              "rendaPerCapita": 450.00,
              "criterioVulnerabilidade": false
            }
            """;

    private static final String EXEMPLO_CADASTRO_INLINE = """
            {
              "responsavel": {
                "nome": "Responsável Exemplo",
                "cpf": "000.000.000-00",
                "telefone": "(00) 00000-0000",
                "email": "responsavel@exemplo.invalid",
                "endereco": {
                  "rua": "Rua das Flores",
                  "numero": 100,
                  "bairro": "Centro",
                  "cep": "00000-000",
                  "cidade": "Município Demo",
                  "estado": "SP",
                  "latitude": -23.5505,
                  "longitude": -46.6333
                }
              },
              "crianca": {
                "nome": "Maria Exemplo",
                "dataNascimento": "2024-03-15"
              },
              "turmaId": 1,
              "rendaPerCapita": 450.00,
              "criterioVulnerabilidade": false
            }
            """;

    private final InscricaoService inscricaoService;
    private final ConsultaInscricaoService consultaInscricaoService;

    @SaCheckPermission("inscricao:inscricao:list")
    @GetMapping
    public List<ConsultaInscricaoService.InscricaoResumo> listar() {
        return consultaInscricaoService.listarInscricoes();
    }

    @Operation(
            summary = "Registra a inscrição da criança na fila de uma turma",
            description = "Aceita exatamente um de dois modos: criança já cadastrada (criancaId) ou cadastro"
                    + " inline (responsavel + crianca).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                @ExampleObject(
                                        name = "Criança já cadastrada",
                                        value = EXEMPLO_CRIANCA_JA_CADASTRADA),
                                @ExampleObject(name = "Cadastro inline", value = EXEMPLO_CADASTRO_INLINE)
                            })))
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
