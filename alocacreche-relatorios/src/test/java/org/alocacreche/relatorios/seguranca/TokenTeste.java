package org.alocacreche.relatorios.seguranca;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Suporte dos ITs de seguranca: emite JWTs com o mesmo segredo e o mesmo formato de claims do
 * core ({@code municipioId} e {@code perms}), inclusive tokens expirados e com assinatura
 * invalida, e anexa o cabecalho {@code Authorization: Bearer} ao {@link TestRestTemplate}. O
 * segredo e o configurado no contexto Spring do proprio IT ({@code sa-token.jwt-secret-key}).
 */
final class TokenTeste {

    static final String SEGREDO_INVALIDO = "outro-segredo-qualquer-0123456789";
    private static final long UMA_HORA_EM_MILISSEGUNDOS = 3_600_000L;
    private static final long UMA_HORA_EM_SEGUNDOS = 3_600L;

    private TokenTeste() {
    }

    static String valido(Long municipioId, List<String> perms) {
        return new StpLogicJwtForStateless()
                .createTokenValue(1L, "default", UMA_HORA_EM_SEGUNDOS, claims(municipioId, perms));
    }

    static String validoSemMunicipio(List<String> perms) {
        return new StpLogicJwtForStateless()
                .createTokenValue(1L, "default", UMA_HORA_EM_SEGUNDOS, Map.of("perms", perms));
    }

    static String expirado(Long municipioId, List<String> perms) {
        return montar(
                SaManager.getConfig().getJwtSecretKey(),
                System.currentTimeMillis() - UMA_HORA_EM_MILISSEGUNDOS,
                municipioId,
                perms);
    }

    static String comAssinaturaInvalida(Long municipioId, List<String> perms) {
        return montar(
                SEGREDO_INVALIDO,
                System.currentTimeMillis() + UMA_HORA_EM_MILISSEGUNDOS,
                municipioId,
                perms);
    }

    static void usarToken(TestRestTemplate restTemplate, String token) {
        restTemplate.getRestTemplate().getInterceptors().removeIf(InterceptadorDeToken.class::isInstance);
        if (token != null) {
            restTemplate.getRestTemplate().getInterceptors().add(new InterceptadorDeToken(token));
        }
    }

    private static String montar(String segredo, long expiraEmMilissegundos, Long municipioId, List<String> perms) {
        Map<String, Object> payload = new HashMap<>(claims(municipioId, perms));
        payload.put(SaJwtUtil.LOGIN_TYPE, new StpLogicJwtForStateless().getLoginType());
        payload.put(SaJwtUtil.LOGIN_ID, 1L);
        payload.put(SaJwtUtil.EFF, expiraEmMilissegundos);
        return SaJwtUtil.createToken(payload, segredo);
    }

    private static Map<String, Object> claims(Long municipioId, List<String> perms) {
        return Map.of("municipioId", municipioId, "perms", perms);
    }

    private record InterceptadorDeToken(String token) implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return execution.execute(request, body);
        }
    }
}
