package org.alocacreche.relatorios.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import com.redis.testcontainers.RedisContainer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Prova a configuracao stateless do Sa-Token no MSRelatorios (AUTH-12 a AUTH-14, AUTH-19): negar
 * por padrao, {@code @SaIgnore} liberando, 401 e 403 com corpo JSON. Usa um controller de teste
 * interno, que nunca existe em {@code src/main}, e o {@link TokenTeste} para emitir os tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Sa-Token stateless no MSRelatorios: nega por padrao, 401 e 403")
class SegurancaConfigIT {

    private static final String PERMISSAO_EXIGIDA = "relatorios:ocupacao:list";
    private static final Long MUNICIPIO = 1L;

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @Container
    static RedisContainer redis =
            new RedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private SegurancaConfig segurancaConfig;

    @BeforeEach
    void limparToken() {
        TokenTeste.usarToken(restTemplate, null);
    }

    @Test
    @DisplayName("endpoint sem anotacao e sem token responde 401 com corpo JSON de erro")
    void endpointSemAnotacaoESemTokenResponde401() {
        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/sem-anotacao", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("endpoint sem anotacao com token valido responde 200")
    void endpointSemAnotacaoComTokenValidoResponde200() {
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(MUNICIPIO, List.of()));

        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/sem-anotacao", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("endpoint com @SaIgnore responde 200 sem token")
    void endpointComSaIgnoreResponde200SemToken() {
        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/aberto", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("token com a permissao exigida responde 200")
    void tokenComAPermissaoResponde200() {
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(MUNICIPIO, List.of(PERMISSAO_EXIGIDA)));

        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/protegido", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("token sem a permissao exigida responde 403 com corpo JSON de erro")
    void tokenSemAPermissaoResponde403() {
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(MUNICIPIO, List.of("relatorios:fila:list")));

        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/protegido", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("token com a permissao curinga * responde 200 em endpoint protegido")
    void tokenComCuringaResponde200() {
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(MUNICIPIO, List.of("*")));

        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/protegido", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("token malformado responde 401 com corpo JSON de erro")
    void tokenMalformadoResponde401() {
        TokenTeste.usarToken(restTemplate, "isto-nao-e-um-jwt");

        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/protegido", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("token com assinatura invalida responde 401")
    void tokenComAssinaturaInvalidaResponde401() {
        TokenTeste.usarToken(restTemplate, TokenTeste.comAssinaturaInvalida(MUNICIPIO, List.of("*")));

        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/protegido", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertCorpoJsonComErro(resposta);
    }

    @Test
    @DisplayName("token expirado responde 401")
    void tokenExpiradoResponde401() {
        TokenTeste.usarToken(restTemplate, TokenTeste.expirado(MUNICIPIO, List.of("*")));

        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-seguranca/protegido", String.class);

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
