package org.alocacreche.core.comum.seguranca.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.secure.BCrypt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.alocacreche.core.comum.seguranca.domain.PerfilUsuario;
import org.alocacreche.core.comum.seguranca.domain.Usuario;
import org.alocacreche.core.comum.seguranca.repository.UsuarioRepository;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Prova ponta a ponta o {@code POST /api/v1/auth/login} com Postgres real (AUTH-01, AUTH-02,
 * AUTH-03, AUTH-05, AUTH-08, AUTH-09, AUTH-10). Um controller de teste interno ao proprio IT
 * prova que o token emitido abre os endpoints protegidos e que o login e o unico aberto. O
 * container sobe num bloco estatico porque o ciclo PER_CLASS cria a instancia (e o contexto
 * Spring) antes do callback do {@code @Container}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("POST /api/v1/auth/login ponta a ponta")
class AuthControllerIT {

    private static final String CAMINHO_LOGIN = "/api/v1/auth/login";
    private static final String SENHA_CORRETA = "SenhaCorreta#2026";
    private static final String DOMINIO = "@prefeitura.gov.br";

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
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private MunicipioRepository municipioRepository;
    @Autowired private StpLogicJwtForStateless stpLogic;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @PersistenceContext private EntityManager entityManager;

    private Long municipioA;
    private Long municipioB;

    @BeforeAll
    void prepararMunicipiosEUsuarios() {
        municipioA = criarMunicipio();
        municipioB = criarMunicipio();
        for (PerfilUsuario perfil : PerfilUsuario.values()) {
            criarUsuario(municipioA, emailDo(perfil), perfil, true);
        }
        criarUsuario(municipioA, "inativo" + DOMINIO, PerfilUsuario.GESTOR, false);
        criarUsuario(municipioB, "so.no.b" + DOMINIO, PerfilUsuario.GESTOR, true);
    }

    @ParameterizedTest(name = "perfil {0}")
    @EnumSource(PerfilUsuario.class)
    @DisplayName("login correto devolve 200 com token Bearer, expiracao e as claims do perfil")
    void loginCorretoDevolveTokenComClaimsDoPerfil(PerfilUsuario perfil) throws Exception {
        Instant antes = Instant.now();

        ResponseEntity<String> resposta = login(emailDo(perfil), SENHA_CORRETA, municipioA);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode corpo = objectMapper.readTree(resposta.getBody());
        assertThat(corpo.get("tipo").asText()).isEqualTo("Bearer");
        assertThat(Instant.parse(corpo.get("expiraEm").asText()))
                .isAfter(antes.plusSeconds(28_000))
                .isBefore(Instant.now().plusSeconds(28_801));
        String token = corpo.get("token").asText();
        assertThat(((Number) stpLogic.getExtra(token, "municipioId")).longValue()).isEqualTo(municipioA);
        assertThat((List<?>) stpLogic.getExtra(token, "perms"))
                .map(String::valueOf)
                .containsExactlyElementsOf(perfil.permissoes());
    }

    @Test
    @DisplayName("senha errada responde 401 generico")
    void senhaErradaResponde401() {
        ResponseEntity<String> resposta = login(emailDo(PerfilUsuario.GESTOR), "SenhaErrada#999", municipioA);

        assertCredenciaisInvalidas(resposta);
    }

    @Test
    @DisplayName("usuarios criados por docs/mock-data/usuarios-demo.sql (hash pgcrypto) fazem login com o perfil certo")
    void usuariosDoScriptDeDemoFazemLogin() throws Exception {
        String senhaDemo = "SenhaDemo#2026";
        String gestor = "gestor.demo" + DOMINIO;
        String atendente = "atendente.demo" + DOMINIO;
        String admin = "admin.demo" + DOMINIO;

        // Duas execucoes seguidas provam a idempotencia (ON CONFLICT DO UPDATE).
        for (int execucao = 0; execucao < 2; execucao++) {
            ExecResult resultado = executarScriptDeUsuarios(
                    postgres.getUsername(), senhaDemo, gestor, atendente, admin, municipioA);
            assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        }

        Map<String, PerfilUsuario> esperados = Map.of(
                gestor, PerfilUsuario.GESTOR, atendente, PerfilUsuario.ATENDENTE, admin, PerfilUsuario.ADMIN);
        for (Map.Entry<String, PerfilUsuario> esperado : esperados.entrySet()) {
            ResponseEntity<String> resposta = login(esperado.getKey(), senhaDemo, municipioA);
            assertThat(resposta.getStatusCode()).as(esperado.getKey()).isEqualTo(HttpStatus.OK);
            String token = objectMapper.readTree(resposta.getBody()).get("token").asText();
            assertThat((List<?>) stpLogic.getExtra(token, "perms"))
                    .map(String::valueOf)
                    .containsExactlyElementsOf(esperado.getValue().permissoes());
        }
        assertThat(login(gestor, "SenhaErrada#999", municipioA).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("script de usuarios da demo com municipio inexistente falha (exit code != 0) e nao insere nada")
    void scriptDeUsuariosFalhaComMunicipioInexistente() throws Exception {
        String gestor = "gestor.sem.municipio" + DOMINIO;

        ExecResult resultado = executarScriptDeUsuarios(
                postgres.getUsername(), "SenhaDemo#2026", gestor, "atendente.sem.municipio" + DOMINIO, null, 987_654L);

        assertThat(resultado.getExitCode()).isNotZero();
        assertThat(resultado.getStderr()).contains("987654 nao existe");
        assertThat(login(gestor, "SenhaDemo#2026", 987_654L).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("script de usuarios da demo sem senha ou sem email obrigatorio falha (exit code != 0) com mensagem clara")
    void scriptDeUsuariosFalhaSemVariavelObrigatoria() throws Exception {
        ExecResult semSenha = executarScriptDeUsuarios(
                postgres.getUsername(), null, "g" + DOMINIO, "a" + DOMINIO, null, municipioA);
        ExecResult semGestor = executarScriptDeUsuarios(
                postgres.getUsername(), "SenhaDemo#2026", null, "a" + DOMINIO, null, municipioA);
        ExecResult semAtendente = executarScriptDeUsuarios(
                postgres.getUsername(), "SenhaDemo#2026", "g" + DOMINIO, null, null, municipioA);

        assertThat(semSenha.getExitCode()).isNotZero();
        assertThat(semSenha.getStderr()).contains("-v senha=");
        assertThat(semGestor.getExitCode()).isNotZero();
        assertThat(semGestor.getStderr()).contains("-v email_gestor=");
        assertThat(semAtendente.getExitCode()).isNotZero();
        assertThat(semAtendente.getStderr()).contains("-v email_atendente=");
    }

    @Test
    @DisplayName("2a execucao do script com OUTRA senha troca a senha: a nova entra e a antiga responde 401")
    void segundaExecucaoComOutraSenhaTrocaASenha() throws Exception {
        String gestor = "gestor.troca" + DOMINIO;
        String atendente = "atendente.troca" + DOMINIO;
        String senhaAntiga = "SenhaAntiga#2026";
        String senhaNova = "SenhaNova#2026";

        ExecResult primeira = executarScriptDeUsuarios(postgres.getUsername(), senhaAntiga, gestor, atendente, null, municipioA);
        assertThat(login(gestor, senhaAntiga, municipioA).getStatusCode()).isEqualTo(HttpStatus.OK);
        ExecResult segunda = executarScriptDeUsuarios(postgres.getUsername(), senhaNova, gestor, atendente, null, municipioA);

        assertThat(primeira.getExitCode()).as(primeira.getStderr()).isZero();
        assertThat(segunda.getExitCode()).as(segunda.getStderr()).isZero();
        assertThat(login(gestor, senhaNova, municipioA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(gestor, senhaAntiga, municipioA).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("script de usuarios da demo reativa um usuario que estava inativo")
    void scriptReativaUsuarioInativo() throws Exception {
        String gestor = "gestor.inativo" + DOMINIO;
        String senhaDemo = "SenhaDemo#2026";
        criarUsuario(municipioA, gestor, PerfilUsuario.GESTOR, false);
        assertThat(login(gestor, SENHA_CORRETA, municipioA).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ExecResult resultado = executarScriptDeUsuarios(
                postgres.getUsername(), senhaDemo, gestor, "atendente.reativa" + DOMINIO, null, municipioA);

        assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        assertThat(login(gestor, senhaDemo, municipioA).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("script de usuarios da demo funciona com a role da aplicacao (sujeita a RLS, sem BYPASSRLS)")
    void scriptFuncionaComARoleSujeitaARls() throws Exception {
        String gestor = "gestor.rls" + DOMINIO;
        String senhaDemo = "SenhaDemo#2026";

        ExecResult resultado = executarScriptDeUsuarios(
                "alocacreche_app", senhaDemo, gestor, "atendente.rls" + DOMINIO, null, municipioA);

        assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        assertThat(login(gestor, senhaDemo, municipioA).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Roda docs/mock-data/usuarios-demo.sql dentro do container, sem passar ON_ERROR_STOP: o proprio
     * script precisa parar no erro. Variavel nula nao e passada ao psql.
     */
    private ExecResult executarScriptDeUsuarios(
            String usuarioPostgres, String senha, String emailGestor, String emailAtendente, String emailAdmin, Long municipioId)
            throws Exception {
        Path script = Path.of("..", "docs", "mock-data", "usuarios-demo.sql").toAbsolutePath().normalize();
        postgres.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/usuarios-demo.sql");
        List<String> comando = new ArrayList<>(List.of("psql", "-U", usuarioPostgres, "-d", postgres.getDatabaseName()));
        Map<String, String> variaveis = new HashMap<>();
        variaveis.put("senha", senha);
        variaveis.put("email_gestor", emailGestor);
        variaveis.put("email_atendente", emailAtendente);
        variaveis.put("email_admin", emailAdmin);
        variaveis.put("municipio_id", String.valueOf(municipioId));
        variaveis.forEach((nome, valor) -> {
            if (valor != null) {
                comando.add("-v");
                comando.add(nome + "=" + valor);
            }
        });
        comando.addAll(List.of("-f", "/tmp/usuarios-demo.sql"));
        return postgres.execInContainer(comando.toArray(String[]::new));
    }

    @Test
    @DisplayName("email inexistente responde 401 generico")
    void emailInexistenteResponde401() {
        ResponseEntity<String> resposta = login("nao.existe" + DOMINIO, SENHA_CORRETA, municipioA);

        assertCredenciaisInvalidas(resposta);
    }

    @Test
    @DisplayName("usuario inativo responde 401 generico")
    void usuarioInativoResponde401() {
        ResponseEntity<String> resposta = login("inativo" + DOMINIO, SENHA_CORRETA, municipioA);

        assertCredenciaisInvalidas(resposta);
    }

    @Test
    @DisplayName("municipio inexistente responde 401 generico")
    void municipioInexistenteResponde401() {
        ResponseEntity<String> resposta = login(emailDo(PerfilUsuario.GESTOR), SENHA_CORRETA, 987_654L);

        assertCredenciaisInvalidas(resposta);
    }

    @Test
    @DisplayName("email de usuario de outro municipio responde 401 generico")
    void emailDeOutroMunicipioResponde401() {
        ResponseEntity<String> resposta = login("so.no.b" + DOMINIO, SENHA_CORRETA, municipioA);

        assertCredenciaisInvalidas(resposta);
    }

    @Test
    @DisplayName("as cinco falhas de credencial devolvem exatamente o mesmo corpo")
    void falhasDeCredencialDevolvemOMesmoCorpo() {
        List<String> corpos = new ArrayList<>();
        corpos.add(login(emailDo(PerfilUsuario.GESTOR), "SenhaErrada#999", municipioA).getBody());
        corpos.add(login("nao.existe" + DOMINIO, SENHA_CORRETA, municipioA).getBody());
        corpos.add(login("inativo" + DOMINIO, SENHA_CORRETA, municipioA).getBody());
        corpos.add(login(emailDo(PerfilUsuario.GESTOR), SENHA_CORRETA, 987_654L).getBody());
        corpos.add(login("so.no.b" + DOMINIO, SENHA_CORRETA, municipioA).getBody());

        assertThat(corpos).hasSize(5).containsOnly(corpos.get(0));
    }

    @Test
    @DisplayName("corpo invalido responde 400 listando os campos invalidos")
    void corpoInvalidoResponde400ComOsCampos() throws Exception {
        Map<String, Object> corpoInvalido = new HashMap<>();
        corpoInvalido.put("email", "isto-nao-e-um-email");
        corpoInvalido.put("senha", " ");
        corpoInvalido.put("municipioId", 0);

        ResponseEntity<String> resposta = enviarJson(objectMapper.writeValueAsString(corpoInvalido));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode campos = objectMapper.readTree(resposta.getBody()).get("campos");
        assertThat(campos).isNotNull();
        List<String> nomes = new ArrayList<>();
        campos.forEach(campo -> nomes.add(campo.asText()));
        assertThat(nomes).containsExactlyInAnyOrder("email", "senha", "municipioId");
    }

    @Test
    @DisplayName("JSON ilegivel responde 400")
    void jsonIlegivelResponde400() {
        ResponseEntity<String> resposta = enviarJson("{isto nao e json");

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("JSON ilegivel devolve o corpo padrao do tratador de requisicao unico")
    void jsonIlegivelDevolveCorpoPadrao() throws Exception {
        ResponseEntity<String> resposta = enviarJson("{isto nao e json");

        JsonNode corpo = objectMapper.readTree(resposta.getBody());
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(corpo.get("erro").asText()).isEqualTo("Requisição inválida");
        assertThat(corpo.has("campos")).isFalse();
    }

    @Test
    @DisplayName("endpoint de teste sem anotacao responde 401 sem token")
    void endpointSemAnotacaoResponde401SemToken() {
        ResponseEntity<String> resposta = restTemplate.getForEntity("/teste-auth/sem-anotacao", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("o login e o unico endpoint com @SaIgnore, ou seja, o unico aberto sem token")
    void loginEOUnicoEndpointAbertoSemToken() {
        List<String> abertos = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entrada :
                handlerMapping.getHandlerMethods().entrySet()) {
            if (entrada.getValue().hasMethodAnnotation(SaIgnore.class)) {
                abertos.add(entrada.getValue().getBeanType().getSimpleName()
                        + "#" + entrada.getValue().getMethod().getName());
            }
        }

        assertThat(abertos).containsExactly("AuthController#login");
    }

    @Test
    @DisplayName("o token de um GESTOR abre o endpoint de relatorios e o de um ATENDENTE recebe 403")
    void tokenEmitidoNoLoginAbreSoOQueOPerfilPermite() throws Exception {
        String tokenGestor = tokenDoLogin(PerfilUsuario.GESTOR);
        String tokenAtendente = tokenDoLogin(PerfilUsuario.ATENDENTE);
        String tokenAdmin = tokenDoLogin(PerfilUsuario.ADMIN);

        assertThat(chamarProtegido(tokenGestor).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chamarProtegido(tokenAtendente).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(chamarProtegido(tokenAdmin).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("login falho e login bem-sucedido nao deixam senha, token nem email nos logs")
    void loginNaoDeixaSegredosNosLogs(CapturedOutput saida) throws Exception {
        String emailFalho = "falha.nos.logs" + DOMINIO;
        String senhaFalha = "SenhaDaFalha#4242";

        login(emailFalho, senhaFalha, municipioA);
        login(emailDo(PerfilUsuario.GESTOR), "OutraSenhaErrada#5151", municipioA);
        String token = tokenDoLogin(PerfilUsuario.GESTOR);

        assertThat(saida.getAll())
                .contains("Falha de login no municipio " + municipioA)
                .doesNotContain(emailFalho)
                .doesNotContain(senhaFalha)
                .doesNotContain("OutraSenhaErrada#5151")
                .doesNotContain(SENHA_CORRETA)
                .doesNotContain(emailDo(PerfilUsuario.GESTOR))
                .doesNotContain(token);
    }

    private void assertCredenciaisInvalidas(ResponseEntity<String> resposta) {
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resposta.getBody()).contains("Credenciais inválidas");
    }

    private ResponseEntity<String> login(String email, String senha, Long municipioId) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("email", email);
        corpo.put("senha", senha);
        corpo.put("municipioId", municipioId);
        try {
            return enviarJson(objectMapper.writeValueAsString(corpo));
        } catch (Exception excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private ResponseEntity<String> enviarJson(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(CAMINHO_LOGIN, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private String tokenDoLogin(PerfilUsuario perfil) throws Exception {
        ResponseEntity<String> resposta = login(emailDo(perfil), SENHA_CORRETA, municipioA);
        return objectMapper.readTree(resposta.getBody()).get("token").asText();
    }

    private ResponseEntity<String> chamarProtegido(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return restTemplate.exchange(
                "/teste-auth/protegido", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String emailDo(PerfilUsuario perfil) {
        return perfil.name().toLowerCase() + DOMINIO;
    }

    private Long criarMunicipio() {
        Municipio municipio = new Municipio();
        municipio.setNome("Municipio de Teste");
        municipio.setUf("SP");
        municipio.setPrazoConfirmacaoDias(5);
        municipio.setConfiguracaoPriorizacao(ConfiguracaoPriorizacao.builder()
                .pesoRenda(0.4)
                .pesoVulnerabilidade(0.3)
                .pesoDistancia(0.2)
                .pesoIrmaoMatriculado(0.1)
                .build());
        return municipioRepository.saveAndFlush(municipio).getId();
    }

    private void criarUsuario(Long municipioId, String email, PerfilUsuario perfil, boolean ativo) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            Usuario usuario = new Usuario();
            usuario.setMunicipioId(municipioId);
            usuario.setEmail(email);
            usuario.setNome("Usuario de teste");
            usuario.setSenhaHash(BCrypt.hashpw(SENHA_CORRETA));
            usuario.setPerfil(perfil);
            usuario.setAtivo(ativo);
            usuarioRepository.saveAndFlush(usuario);
        });
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
    @RequestMapping("/teste-auth")
    static class ControllerDeTeste {

        @GetMapping("/sem-anotacao")
        public String semAnotacao() {
            return "ok";
        }

        @SaCheckPermission("relatorios:ocupacao:list")
        @GetMapping("/protegido")
        public String protegido() {
            return "ok";
        }
    }
}
