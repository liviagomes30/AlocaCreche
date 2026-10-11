package org.alocacreche.relatorios.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contrato OpenAPI do modulo: esquema HTTP Bearer JWT aplicado como requisito global. As operacoes
 * publicas ({@code @SaIgnore}) saem sem cadeado e as protegidas ganham a permissao exigida, ambas
 * pelo {@link PermissaoOperationCustomizer}. O documento e a UI so existem com
 * {@code alocacreche.swagger.habilitado=true} (ver ADR 0008).
 */
@Configuration
public class OpenApiConfig {

    static final String ESQUEMA_BEARER = "bearerAuth";

    @Bean
    public OpenAPI openApi() {
        SecurityScheme esquemaBearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Token JWT obtido em POST /api/v1/auth/login. Cole apenas o token, sem o prefixo Bearer.");
        return new OpenAPI()
                .info(new Info()
                        .title("AlocaCreche - API do MSRelatorios")
                        .version("v1")
                        .description("Projeções de leitura: ocupação das turmas, fila de espera e indicadores de matrícula, mais o painel público. Esta documentação é aberta, mas executar uma operação protegida exige o token JWT emitido pelo core em POST /api/v1/auth/login (o mesmo token vale aqui): clique em Authorize e cole o token (sem o prefixo Bearer). O municipioId informado precisa ser o do token."))
                .components(new Components().addSecuritySchemes(ESQUEMA_BEARER, esquemaBearer))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_BEARER));
    }
}
