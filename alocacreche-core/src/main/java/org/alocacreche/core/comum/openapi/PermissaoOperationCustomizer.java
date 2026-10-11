package org.alocacreche.core.comum.openapi;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.reflect.Method;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * Deriva da seguranca do codigo o que o Swagger mostra, sem anotar controller por controller: a
 * operacao com {@code @SaIgnore} fica sem cadeado (requisito de seguranca vazio) e a que tem
 * {@code @SaCheckPermission} informa a permissao exigida na descricao e as respostas 401 e 403.
 */
@Component
public class PermissaoOperationCustomizer implements OperationCustomizer {

    static final String ROTULO_PERMISSAO = "Permissão exigida";
    private static final String SEPARADOR_DE_PARAGRAFO = "\n\n";

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Method metodo = handlerMethod.getMethod();
        if (AnnotatedElementUtils.hasAnnotation(metodo, SaIgnore.class)) {
            operation.setSecurity(List.of());
            return operation;
        }
        SaCheckPermission permissao = AnnotatedElementUtils.findMergedAnnotation(metodo, SaCheckPermission.class);
        if (permissao != null) {
            operation.setDescription(descricaoComPermissao(operation.getDescription(), permissao.value()));
            adicionarRespostaSeAusente(operation, "401", "Sem token, token inválido ou expirado.");
            adicionarRespostaSeAusente(operation, "403", "O perfil do token não tem a permissão exigida.");
        }
        return operation;
    }

    private static String descricaoComPermissao(String descricaoAtual, String[] permissoes) {
        String lista = "`" + String.join("`, `", permissoes) + "`";
        String linhaPermissao = "**" + ROTULO_PERMISSAO + ":** " + lista;
        return descricaoAtual == null || descricaoAtual.isBlank()
                ? linhaPermissao
                : descricaoAtual + SEPARADOR_DE_PARAGRAFO + linhaPermissao;
    }

    private static void adicionarRespostaSeAusente(Operation operation, String codigo, String descricao) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        if (!operation.getResponses().containsKey(codigo)) {
            operation.getResponses().addApiResponse(codigo, new ApiResponse().description(descricao));
        }
    }
}
