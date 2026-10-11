package org.alocacreche.core.comum.seguranca.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.alocacreche.core.comum.seguranca.config.SegurancaConfig.SaInterceptorSemRedespachoDeErro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Garante que a excecao do Swagger no interceptor e estreita: so GET dos documentos, so com o
 * Swagger habilitado, e nunca para uma rota de negocio. A excecao vive no metodo do interceptor,
 * nao em {@code excludePathPatterns} (proibido pelo CLAUDE.md).
 */
@DisplayName("Excecao do Swagger no SaInterceptor: so GET dos documentos e so quando habilitado")
class SwaggerLiberacaoTest {

    @ParameterizedTest(name = "{0} {1} e leitura da documentacao")
    @CsvSource({
        "GET, /swagger-ui.html",
        "GET, /swagger-ui/index.html",
        "GET, /swagger-ui/swagger-ui.css",
        "GET, /v3/api-docs",
        "GET, /v3/api-docs/swagger-config",
        "GET, /v3/api-docs.yaml"
    })
    void getDosDocumentosELiberado(String metodo, String caminho) {
        assertThat(SegurancaConfig.ehLeituraDaDocumentacao(metodo, caminho)).isTrue();
    }

    @ParameterizedTest(name = "{0} {1} nao e leitura da documentacao")
    @CsvSource({
        "POST, /v3/api-docs",
        "PUT, /swagger-ui.html",
        "DELETE, /swagger-ui/index.html",
        "OPTIONS, /v3/api-docs",
        "HEAD, /v3/api-docs",
        "GET, /api/v1/matricula/creches",
        "GET, /swagger-ui-falso/index.html",
        "GET, /swagger-uix.html",
        "GET, /v3/api-docs-falso",
        "GET, /v3/api-docsx",
        "GET, /swagger-ui/../api/v1/matricula/creches",
        "GET, /v3/api-docs/../api/v1/inscricao/criancas",
        "GET, /swagger-ui/..;/api/v1/inscricao/criancas",
        "GET, /actuator/health"
    })
    void outrasRequisicoesNaoSaoLiberadas(String metodo, String caminho) {
        assertThat(SegurancaConfig.ehLeituraDaDocumentacao(metodo, caminho)).isFalse();
    }

    @Test
    @DisplayName("Swagger habilitado: GET /v3/api-docs passa sem rodar a checagem de login")
    void habilitadoLiberaOGetDoDocumento() throws Exception {
        AtomicInteger checagens = new AtomicInteger();
        SaInterceptorSemRedespachoDeErro interceptor = interceptor(true, checagens);

        boolean seguir = interceptor.preHandle(requisicao("GET", "/v3/api-docs"), new MockHttpServletResponse(), new Object());

        assertThat(seguir).isTrue();
        assertThat(checagens).hasValue(0);
    }

    @Test
    @DisplayName("Swagger desabilitado: GET /v3/api-docs cai na checagem de login (nega por padrao)")
    void desabilitadoNaoLiberaNada() throws Exception {
        AtomicInteger checagens = new AtomicInteger();
        SaInterceptorSemRedespachoDeErro interceptor = interceptor(false, checagens);

        interceptor.preHandle(requisicao("GET", "/v3/api-docs"), new MockHttpServletResponse(), new Object());
        interceptor.preHandle(requisicao("GET", "/swagger-ui.html"), new MockHttpServletResponse(), new Object());

        assertThat(checagens).hasValue(2);
    }

    @Test
    @DisplayName("Swagger habilitado: rota de negocio e POST nos documentos continuam passando pela checagem")
    void habilitadoNaoLiberaRotaDeNegocioNemMetodoDiferente() throws Exception {
        AtomicInteger checagens = new AtomicInteger();
        SaInterceptorSemRedespachoDeErro interceptor = interceptor(true, checagens);

        interceptor.preHandle(requisicao("GET", "/api/v1/matricula/creches/proximas"), new MockHttpServletResponse(), new Object());
        interceptor.preHandle(requisicao("POST", "/v3/api-docs"), new MockHttpServletResponse(), new Object());

        assertThat(checagens).hasValue(2);
    }

    @Test
    @DisplayName("o caminho normalizado do container tambem precisa ser de documentacao (traversal)")
    void caminhoNormalizadoDiferenteNaoPassa() throws Exception {
        AtomicInteger checagens = new AtomicInteger();
        SaInterceptorSemRedespachoDeErro interceptor = interceptor(true, checagens);
        MockHttpServletRequest requisicao = requisicao("GET", "/swagger-ui/index.html");
        requisicao.setServletPath("/api/v1/matricula/creches");

        interceptor.preHandle(requisicao, new MockHttpServletResponse(), new Object());

        assertThat(checagens).hasValue(1);
    }

    private static SaInterceptorSemRedespachoDeErro interceptor(boolean habilitado, AtomicInteger checagens) {
        return new SaInterceptorSemRedespachoDeErro(handler -> checagens.incrementAndGet(), habilitado);
    }

    private static MockHttpServletRequest requisicao(String metodo, String caminho) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest(metodo, caminho);
        requisicao.setServletPath(caminho);
        return requisicao;
    }
}
