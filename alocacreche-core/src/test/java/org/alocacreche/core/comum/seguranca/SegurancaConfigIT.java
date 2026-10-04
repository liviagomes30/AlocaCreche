package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.stp.StpInterface;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova a configuracao stateless do Sa-Token no core (AUTH-09, AUTH-12 a AUTH-14): negar por
 * padrao, {@code @SaIgnore} liberando, 401 e 403 com corpo JSON. Usa um controller de teste
 * interno, que nunca existe em {@code src/main}. O {@code StpInterface} usado aqui e o
 * {@link StpInterfaceDeTeste}, porque o de producao pertence a nucleo-bootstrap-comum.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Sa-Token stateless no core: nega por padrao, 401 e 403")
class SegurancaConfigIT {

    private static final String PERMISSAO_EXIGIDA = "relatorios:ocupacao:list";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private SegurancaConfig segurancaConfig;
    @Autowired private StpLogicJwtForStateless stpLogic;

    @Test
    @DisplayName("endpoint sem anotacao e sem token responde 401 com corpo JSON de erro")
    void endpointSemAnotacaoESemTokenResponde401() {
        ResponseEntity<String> resposta = chamar("/teste-seguranca/sem-anotacao", null);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("endpoint sem anotacao com token valido responde 200")
    void endpointSemAnotacaoComTokenValidoResponde200() {
        ResponseEntity<String> resposta = chamar("/teste-seguranca/sem-anotacao", tokenCom(List.of()));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("endpoint com @SaIgnore responde 200 sem token")
    void endpointComSaIgnoreResponde200SemToken() {
        ResponseEntity<String> resposta = chamar("/teste-seguranca/aberto", null);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("token com a permissao exigida responde 200")
    void tokenComAPermissaoResponde200() {
        ResponseEntity<String> resposta =
                chamar("/teste-seguranca/protegido", tokenCom(List.of(PERMISSAO_EXIGIDA)));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("token sem a permissao exigida responde 403 com corpo JSON de erro")
    void tokenSemAPermissaoResponde403() {
        ResponseEntity<String> resposta =
                chamar("/teste-seguranca/protegido", tokenCom(List.of("relatorios:fila:list")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("token com a permissao curinga * responde 200 em endpoint protegido")
    void tokenComCuringaResponde200() {
        ResponseEntity<String> resposta = chamar("/teste-seguranca/protegido", tokenCom(List.of("*")));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("token malformado responde 401 com corpo JSON de erro")
    void tokenMalformadoResponde401() {
        ResponseEntity<String> resposta = chamar("/teste-seguranca/protegido", "isto-nao-e-um-jwt");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("token com assinatura invalida responde 401")
    void tokenComAssinaturaInvalidaResponde401() {
        String token = montarToken(
                "outro-segredo-qualquer-0123456789", System.currentTimeMillis() + 3_600_000L, List.of("*"));

        ResponseEntity<String> resposta = chamar("/teste-seguranca/protegido", token);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("token expirado responde 401")
    void tokenExpiradoResponde401() {
        String token = montarToken(
                SaManager.getConfig().getJwtSecretKey(), System.currentTimeMillis() - 1_000L, List.of("*"));

        ResponseEntity<String> resposta = chamar("/teste-seguranca/protegido", token);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("o interceptor e registrado sem nenhum caminho de exclusao")
    void interceptorNaoTemCaminhoDeExclusao() {
        InterceptorRegistry registry = new InterceptorRegistry();

        segurancaConfig.addInterceptors(registry);

        List<?> registros = (List<?>) ReflectionTestUtils.getField(registry, "registrations");
        assertThat(registros).hasSize(1);
        Object excluidos = ReflectionTestUtils.getField(registros.get(0), "excludePatterns");
        assertThat(excluidos == null || ((List<?>) excluidos).isEmpty()).isTrue();
    }

    private ResponseEntity<String> chamar(String caminho, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return restTemplate.exchange(caminho, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String tokenCom(List<String> permissoes) {
        return stpLogic.createTokenValue(
                1L, "default", 3600, Map.of("municipioId", 1L, "perms", permissoes));
    }

    private String montarToken(String segredo, long expiraEmMilissegundos, List<String> permissoes) {
        return SaJwtUtil.createToken(
                Map.of(
                        SaJwtUtil.LOGIN_TYPE, stpLogic.getLoginType(),
                        SaJwtUtil.LOGIN_ID, 1L,
                        SaJwtUtil.EFF, expiraEmMilissegundos,
                        "municipioId", 1L,
                        "perms", permissoes),
                segredo);
    }

    private static void assertCorpoJsonComErro(ResponseEntity<String> resposta) {
        assertThat(resposta.getHeaders().getContentType()).isNotNull();
        assertThat(resposta.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(resposta.getBody()).contains("\"erro\"");
    }

    @TestConfiguration
    static class ControllerDeTesteConfig {

        @Bean
        ControllerDeTeste controllerDeTeste() {
            return new ControllerDeTeste();
        }

        @Bean
        StpInterface stpInterfaceDeTeste() {
            return new StpInterfaceDeTeste();
        }
    }

    @Controller
    @ResponseBody
    @RequestMapping("/teste-seguranca")
    static class ControllerDeTeste {

        @GetMapping("/sem-anotacao")
        public String semAnotacao() {
            return "ok";
        }

        @SaIgnore
        @GetMapping("/aberto")
        public String aberto() {
            return "ok";
        }

        @SaCheckPermission(PERMISSAO_EXIGIDA)
        @GetMapping("/protegido")
        public String protegido() {
            return "ok";
        }
    }
}
