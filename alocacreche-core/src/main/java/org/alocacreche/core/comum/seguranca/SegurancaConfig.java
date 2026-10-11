package org.alocacreche.core.comum.seguranca;

import cn.dev33.satoken.fun.SaParamFunction;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SegurancaConfig implements WebMvcConfigurer {

    private static final String METODO_LEITURA = "GET";
    private static final String PAGINA_SWAGGER = "/swagger-ui.html";
    private static final String PREFIXO_RECURSOS_SWAGGER = "/swagger-ui/";
    private static final String DOCUMENTO_OPENAPI = "/v3/api-docs";
    private static final List<String> DOCUMENTOS_OPENAPI_EXATOS = List.of(DOCUMENTO_OPENAPI, DOCUMENTO_OPENAPI + ".yaml");
    private static final String PREFIXO_DOCUMENTOS_OPENAPI = DOCUMENTO_OPENAPI + "/";

    private final boolean swaggerHabilitado;

    /**
     * O Swagger so abre a excecao de leitura no interceptor quando a propriedade
     * {@code alocacreche.swagger.habilitado} esta ligada; sem ela (padrao seguro) nada e liberado.
     */
    public SegurancaConfig(@Value("${alocacreche.swagger.habilitado:false}") boolean swaggerHabilitado) {
        this.swaggerHabilitado = swaggerHabilitado;
    }

    @Bean
    public StpLogicJwtForStateless stpLogicJwtForStateless() {
        return new StpLogicJwtForStateless();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptorSemRedespachoDeErro(handler -> StpUtil.checkLogin(), swaggerHabilitado))
                .addPathPatterns("/**");
    }

    /**
     * Indica se a requisicao e uma leitura (GET) da pagina do Swagger UI, dos seus recursos
     * estaticos ou do documento OpenAPI. Caminhos com {@code ..} ou {@code ;} nunca casam, para
     * que um prefixo do Swagger nao sirva de ponte para uma rota de negocio.
     */
    static boolean ehLeituraDaDocumentacao(String metodo, String caminho) {
        if (!METODO_LEITURA.equals(metodo) || caminho == null || caminho.contains("..") || caminho.contains(";")) {
            return false;
        }
        return PAGINA_SWAGGER.equals(caminho)
                || caminho.startsWith(PREFIXO_RECURSOS_SWAGGER)
                || DOCUMENTOS_OPENAPI_EXATOS.contains(caminho)
                || caminho.startsWith(PREFIXO_DOCUMENTOS_OPENAPI);
    }

    /**
     * Interceptor do Sa-Token com duas excecoes por metodo (nunca por {@code excludePathPatterns}):
     * o redespacho interno de erro do container, onde o contexto do Sa-Token nao existe, e a
     * leitura da documentacao do Swagger quando ele esta habilitado.
     */
    static final class SaInterceptorSemRedespachoDeErro extends SaInterceptor {

        private final boolean swaggerHabilitado;

        SaInterceptorSemRedespachoDeErro(SaParamFunction<Object> auth, boolean swaggerHabilitado) {
            super(auth);
            this.swaggerHabilitado = swaggerHabilitado;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            if (request.getDispatcherType() == DispatcherType.ERROR || ehDocumentacaoLiberada(request)) {
                return true;
            }
            return super.preHandle(request, response, handler);
        }

        /**
         * Confere o caminho bruto da requisicao (sem o contexto) e o caminho normalizado pelo
         * container: os dois precisam ser de documentacao, o que barra traversal.
         */
        private boolean ehDocumentacaoLiberada(HttpServletRequest request) {
            if (!swaggerHabilitado) {
                return false;
            }
            String contexto = request.getContextPath() == null ? "" : request.getContextPath();
            String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
            String caminhoBruto = uri.startsWith(contexto) ? uri.substring(contexto.length()) : uri;
            return ehLeituraDaDocumentacao(request.getMethod(), caminhoBruto)
                    && ehLeituraDaDocumentacao(request.getMethod(), request.getServletPath());
        }
    }
}
