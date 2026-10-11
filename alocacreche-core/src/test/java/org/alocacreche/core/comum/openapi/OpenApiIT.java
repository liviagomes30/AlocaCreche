package org.alocacreche.core.comum.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Prova o contrato OpenAPI e a seguranca do Swagger no core, com o Swagger habilitado: a pagina e o
 * documento abrem sem token, o documento descreve TODAS as rotas dos controllers (comparado com a
 * varredura de {@code @RequestMapping}), o esquema {@code bearerAuth} existe, operacoes protegidas
 * informam a permissao exigida e as publicas (login) ficam sem cadeado. Ao executar, rota de
 * negocio sem token continua 401 e com token valido responde 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OpenAPI e Swagger UI do core: documento aberto, rotas protegidas continuam 401")
class OpenApiIT {

    private static final String PACOTE_RAIZ = "org.alocacreche.core";
    private static final String PARAMETROS_NEGOCIO = "?latitude=-23.5&longitude=-46.6&faixaEtaria=MATERNAL";
    private static final String ROTA_NEGOCIO = "/api/v1/matricula/creches/proximas" + PARAMETROS_NEGOCIO;

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StpLogicJwtForStateless stpLogic;

    private JsonNode documento;

    @BeforeAll
    void baixarODocumento() throws Exception {
        ResponseEntity<byte[]> resposta = restTemplate.getForEntity("/v3/api-docs", byte[].class);
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        documento = objectMapper.readTree(resposta.getBody());
    }

    @Test
    @DisplayName("GET /v3/api-docs responde 200 com JSON OpenAPI sem token")
    void documentoAbreSemToken() {
        assertThat(documento.get("openapi").asText()).startsWith("3.");
        assertThat(documento.get("info").get("title").asText()).isNotBlank();
    }

    @Test
    @DisplayName("GET /swagger-ui.html e a pagina da UI abrem sem token")
    void paginaDoSwaggerAbreSemToken() {
        ResponseEntity<String> atalho = restTemplate.getForEntity("/swagger-ui.html", String.class);
        ResponseEntity<String> pagina = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(atalho.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.FOUND);
        assertThat(pagina.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pagina.getBody()).contains("swagger-ui");
    }

    @Test
    @DisplayName("o documento descreve todas as rotas dos controllers, sem faltar nenhuma")
    void documentoTemTodasAsRotasDosControllers() {
        Set<String> esperadas = rotasDosControllers();

        assertThat(esperadas).isNotEmpty();
        assertThat(rotasDoDocumento()).containsAll(esperadas);
    }

    @Test
    @DisplayName("o esquema bearerAuth (HTTP Bearer JWT) existe e vale como requisito global")
    void esquemaBearerExisteEGlobal() {
        JsonNode esquema = documento.at("/components/securitySchemes/bearerAuth");

        assertThat(esquema.get("type").asText()).isEqualTo("http");
        assertThat(esquema.get("scheme").asText()).isEqualTo("bearer");
        assertThat(esquema.get("bearerFormat").asText()).isEqualTo("JWT");
        assertThat(documento.get("security").toString()).contains("bearerAuth");
    }

    @Test
    @DisplayName("toda operacao protegida informa a permissao exigida e as respostas 401 e 403")
    void operacoesProtegidasInformamAPermissao() {
        List<Method> protegidos = metodosDosControllers().stream()
                .filter(metodo -> AnnotatedElementUtils.hasAnnotation(metodo, SaCheckPermission.class))
                .toList();

        assertThat(protegidos).isNotEmpty();
        for (Method metodo : protegidos) {
            JsonNode operacao = operacaoDe(metodo);
            String permissao = AnnotatedElementUtils.findMergedAnnotation(metodo, SaCheckPermission.class).value()[0];

            assertThat(operacao.path("description").asText())
                    .as(metodo.getName())
                    .contains("Permissão exigida")
                    .contains(permissao);
            assertThat(operacao.get("responses").has("401")).as(metodo.getName()).isTrue();
            assertThat(operacao.get("responses").has("403")).as(metodo.getName()).isTrue();
            assertThat(operacao.has("security") && operacao.get("security").isEmpty())
                    .as("%s nao pode ficar sem cadeado", metodo.getName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("o login (@SaIgnore) aparece sem cadeado e sem exigir permissao")
    void loginFicaSemCadeado() {
        JsonNode login = documento.at("/paths/~1api~1v1~1auth~1login/post");

        assertThat(login.get("security").isArray()).isTrue();
        assertThat(login.get("security")).isEmpty();
        assertThat(login.path("description").asText()).doesNotContain("Permissão exigida");
    }

    @Test
    @DisplayName("toda operacao com @SaIgnore no codigo esta sem cadeado no documento")
    void operacoesPublicasFicamSemCadeado() {
        List<Method> publicos = metodosDosControllers().stream()
                .filter(metodo -> AnnotatedElementUtils.hasAnnotation(metodo, SaIgnore.class))
                .toList();

        assertThat(publicos).isNotEmpty();
        for (Method metodo : publicos) {
            assertThat(operacaoDe(metodo).get("security")).as(metodo.getName()).isNotNull().isEmpty();
        }
    }

    @Test
    @DisplayName("com o Swagger ligado, rota de negocio sem token responde 401")
    void rotaDeNegocioSemTokenResponde401() {
        ResponseEntity<String> resposta = chamar(HttpMethod.GET, ROTA_NEGOCIO, null);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("com o Swagger ligado, rota de negocio com token valido responde 200")
    void rotaDeNegocioComTokenValidoResponde200() {
        String token = stpLogic.createTokenValue(
                1L, "default", 3600, Map.of("municipioId", 1L, "perms", List.of("matricula:creche:list")));

        ResponseEntity<String> resposta = chamar(HttpMethod.GET, ROTA_NEGOCIO, token);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("os documentos aceitam so GET: POST e DELETE neles nao sao atendidos (401 ou 405 do Spring)")
    void documentosNaoAceitamOutrosMetodos() {
        assertThat(chamar(HttpMethod.POST, "/v3/api-docs", null).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(chamar(HttpMethod.DELETE, "/swagger-ui/index.html", null).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("tentativa de sair do prefixo do Swagger por .. nao libera rota de negocio")
    void traversalNaoLiberaRotaDeNegocio() {
        ResponseEntity<String> resposta = restTemplate.exchange(
                URI.create(restTemplate.getRootUri() + "/swagger-ui/../api/v1/matricula/creches/proximas"
                        + PARAMETROS_NEGOCIO),
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);

        // O firewall do Sa-Token barra o ".." antes do interceptor e responde 200 com texto puro (e nao
        // com 401); o que importa e que o corpo nunca seja o da rota de negocio (lista JSON).
        assertThat(resposta.getHeaders().getContentType()).isNotNull();
        assertThat(resposta.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isFalse();
        assertThat(resposta.getBody()).doesNotStartWith("[");
    }

    private ResponseEntity<String> chamar(HttpMethod metodo, String caminho, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return restTemplate.exchange(caminho, metodo, new HttpEntity<>(headers), String.class);
    }

    private Set<String> rotasDoDocumento() {
        Set<String> rotas = new TreeSet<>();
        documento.get("paths").properties().forEach(caminho -> caminho.getValue()
                .fieldNames()
                .forEachRemaining(metodo -> rotas.add(metodo.toUpperCase() + " " + caminho.getKey())));
        return rotas;
    }

    private Set<String> rotasDosControllers() {
        Set<String> rotas = new TreeSet<>();
        for (Method metodo : metodosDosControllers()) {
            rotas.addAll(rotasDe(metodo));
        }
        return rotas;
    }

    private JsonNode operacaoDe(Method metodo) {
        String rota = rotasDe(metodo).iterator().next();
        String verbo = rota.substring(0, rota.indexOf(' ')).toLowerCase();
        String caminho = rota.substring(rota.indexOf(' ') + 1);
        JsonNode operacao = documento.at("/paths/" + caminho.replace("~", "~0").replace("/", "~1") + "/" + verbo);
        assertThat(operacao.isMissingNode()).as("rota %s ausente do documento", rota).isFalse();
        return operacao;
    }

    private static Set<String> rotasDe(Method metodo) {
        RequestMapping classe =
                AnnotatedElementUtils.findMergedAnnotation(metodo.getDeclaringClass(), RequestMapping.class);
        RequestMapping mapeamento = AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
        String prefixo = classe == null || classe.path().length == 0 ? "" : classe.path()[0];
        String[] caminhos = mapeamento.path().length == 0 ? new String[] {""} : mapeamento.path();
        Set<String> rotas = new TreeSet<>();
        for (String caminho : caminhos) {
            for (RequestMethod verbo : mapeamento.method()) {
                rotas.add(verbo.name() + " " + prefixo + caminho);
            }
        }
        return rotas;
    }

    private static List<Method> metodosDosControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        return scanner.findCandidateComponents(PACOTE_RAIZ).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(nome -> ClassUtils.resolveClassName(nome, OpenApiIT.class.getClassLoader()))
                .filter(classe -> !classe.getProtectionDomain().getCodeSource().getLocation().getPath()
                        .contains("test-classes"))
                .flatMap(classe -> Arrays.stream(classe.getDeclaredMethods()))
                .filter(metodo -> AnnotatedElementUtils.hasAnnotation(metodo, RequestMapping.class))
                .toList();
    }
}
