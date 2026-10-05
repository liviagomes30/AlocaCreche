package org.alocacreche.relatorios.seguranca;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Suporte dos testes unitarios de seguranca: liga o Sa-Token stateless (JWT) sem subir o Spring,
 * le o token do cabecalho {@code Authorization: Bearer} de uma requisicao simulada e restaura o
 * estado estatico global do Sa-Token ao fim de cada teste.
 */
class ContextoSaTokenExtension implements BeforeEachCallback, AfterEachCallback {

    static final String SEGREDO = "segredo-de-teste-do-relatorios-0123456789";

    private SaTokenConfig configAnterior;
    private StpLogic stpLogicAnterior;
    private SaTokenContext contextoAnterior;

    @Override
    public void beforeEach(ExtensionContext context) {
        configAnterior = SaManager.getConfig();
        stpLogicAnterior = StpUtil.getStpLogic();
        contextoAnterior = SaManager.getSaTokenContext();
        SaManager.setConfig(new SaTokenConfig()
                .setJwtSecretKey(SEGREDO)
                .setTokenName("Authorization")
                .setTokenPrefix("Bearer")
                .setIsPrint(false));
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        StpUtil.setStpLogic(new StpLogicJwtForStateless());
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        RequestContextHolder.resetRequestAttributes();
        StpUtil.setStpLogic(stpLogicAnterior);
        SaManager.setSaTokenContext(contextoAnterior);
        SaManager.setConfig(configAnterior);
    }

    static String emitirToken(Long municipioId, List<String> perms) {
        return new StpLogicJwtForStateless()
                .createTokenValue(1L, "default", 3600, Map.of("municipioId", municipioId, "perms", perms));
    }

    static String emitirTokenSemClaims() {
        return new StpLogicJwtForStateless().createTokenValue(1L, "default", 3600, Map.of());
    }

    static void requisicaoComToken(String token) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.addHeader("Authorization", "Bearer " + token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(requisicao));
    }
}
