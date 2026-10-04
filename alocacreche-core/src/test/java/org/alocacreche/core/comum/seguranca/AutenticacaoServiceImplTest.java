package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.secure.BCrypt;
import cn.hutool.json.JSONObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regras do login (AUTH-01 a AUTH-04, AUTH-06 a AUTH-08 e AUTH-10): token com municipioId e
 * perms, falha generica identica para toda credencial invalida, hash ficticio para email
 * inexistente, tenant aplicado antes da busca e nenhum dado sensivel em log.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("AutenticacaoServiceImpl")
class AutenticacaoServiceImplTest {

    private static final String SEGREDO = "segredo-de-teste-do-servico-de-login-0123456789";
    private static final long TIMEOUT_SEGUNDOS = 28800L;
    private static final Long MUNICIPIO_ID = 3L;
    private static final String EMAIL = "gestora@prefeitura.gov.br";
    private static final String SENHA = "senha-correta-123";
    private static final String MENSAGEM_GENERICA = "Credenciais inválidas";
    private static final String MOTIVO_NO_LOG = "credenciais invalidas";
    private static final Long USUARIO_ID = 7L;
    private static final String SQL_DO_TENANT = "SELECT set_config('app.current_tenant', ?1, true)";

    private static String hashDaSenha;

    private SaTokenConfig configAnterior;
    private UsuarioRepository usuarioRepository;
    private MunicipioRepository municipioRepository;
    private EntityManager entityManager;
    private Query consultaDoTenant;
    private AutenticacaoServiceImpl servico;

    @BeforeAll
    static void gerarHash() {
        hashDaSenha = BCrypt.hashpw(SENHA);
    }

    @BeforeEach
    void setUp() {
        configAnterior = SaManager.getConfig();
        SaManager.setConfig(new SaTokenConfig().setJwtSecretKey(SEGREDO).setTimeout(TIMEOUT_SEGUNDOS).setIsPrint(false));
        usuarioRepository = mock(UsuarioRepository.class);
        municipioRepository = mock(MunicipioRepository.class);
        entityManager = mock(EntityManager.class);
        consultaDoTenant = mock(Query.class);
        when(entityManager.createNativeQuery(SQL_DO_TENANT)).thenReturn(consultaDoTenant);
        when(consultaDoTenant.setParameter(anyInt(), any())).thenReturn(consultaDoTenant);
        when(municipioRepository.existsById(MUNICIPIO_ID)).thenReturn(true);
        servico = new AutenticacaoServiceImpl(
                usuarioRepository, municipioRepository, entityManager, new StpLogicJwtForStateless());
    }

    @AfterEach
    void tearDown() {
        SaManager.setConfig(configAnterior);
    }

    @Test
    @DisplayName("credencial correta devolve token Bearer com expiracao em 8 horas e as claims do usuario")
    void credencialCorretaDevolveTokenComClaims() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario(PerfilUsuario.GESTOR, true)));

        Instant antes = Instant.now();
        LoginResponse resposta = servico.autenticar(new LoginRequest(EMAIL, SENHA, MUNICIPIO_ID));
        Instant depois = Instant.now();

        assertThat(resposta.tipo()).isEqualTo("Bearer");
        assertThat(resposta.token()).isNotBlank();
        assertThat(resposta.expiraEm())
                .isBetween(antes.plusSeconds(TIMEOUT_SEGUNDOS).minusSeconds(1), depois.plusSeconds(TIMEOUT_SEGUNDOS));
        JSONObject claims = claimsDo(resposta.token());
        assertThat(claims.getLong("municipioId")).isEqualTo(MUNICIPIO_ID);
        assertThat(claims.getJSONArray("perms").toList(String.class)).containsExactlyInAnyOrder(
                "relatorios:ocupacao:list", "relatorios:fila:list", "relatorios:matricula:list");
        assertThat(Instant.ofEpochMilli(claims.getLong(SaJwtUtil.EFF))).isEqualTo(resposta.expiraEm());
    }

    @ParameterizedTest
    @EnumSource(PerfilUsuario.class)
    @DisplayName("a claim perms traz exatamente as permissoes do perfil do usuario")
    void claimPermsTrazAsPermissoesDoPerfil(PerfilUsuario perfil) {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario(perfil, true)));

        LoginResponse resposta = servico.autenticar(new LoginRequest(EMAIL, SENHA, MUNICIPIO_ID));

        assertThat(claimsDo(resposta.token()).getJSONArray("perms").toList(String.class))
                .containsExactlyElementsOf(perfil.permissoes());
    }

    @Test
    @DisplayName("email inexistente no municipio lanca a falha generica")
    void emailInexistenteLancaFalhaGenerica() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servico.autenticar(new LoginRequest(EMAIL, SENHA, MUNICIPIO_ID)))
                .isExactlyInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage(MENSAGEM_GENERICA);
    }

    @Test
    @DisplayName("senha errada lanca a mesma falha generica")
    void senhaErradaLancaFalhaGenerica() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario(PerfilUsuario.GESTOR, true)));

        assertThatThrownBy(() -> servico.autenticar(new LoginRequest(EMAIL, "senha-errada", MUNICIPIO_ID)))
                .isExactlyInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage(MENSAGEM_GENERICA);
    }

    @Test
    @DisplayName("usuario inativo com a senha correta lanca a mesma falha generica")
    void usuarioInativoLancaFalhaGenerica() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario(PerfilUsuario.GESTOR, false)));

        assertThatThrownBy(() -> servico.autenticar(new LoginRequest(EMAIL, SENHA, MUNICIPIO_ID)))
                .isExactlyInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage(MENSAGEM_GENERICA);
    }

    @Test
    @DisplayName("municipio inexistente lanca a mesma falha generica")
    void municipioInexistenteLancaFalhaGenerica() {
        when(municipioRepository.existsById(99L)).thenReturn(false);
        Usuario usuario = usuario(PerfilUsuario.GESTOR, true);
        usuario.setMunicipioId(99L);
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario));

        assertThatThrownBy(() -> servico.autenticar(new LoginRequest(EMAIL, SENHA, 99L)))
                .isExactlyInstanceOf(CredenciaisInvalidasException.class)
                .hasMessage(MENSAGEM_GENERICA);
    }

    @Test
    @DisplayName("email inexistente ainda confere a senha com BCrypt contra um hash ficticio")
    void emailInexistenteConfereSenhaContraHashFicticio() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        try (MockedStatic<BCrypt> bcrypt = mockStatic(BCrypt.class, CALLS_REAL_METHODS)) {
            assertThatThrownBy(() -> servico.autenticar(new LoginRequest(EMAIL, SENHA, MUNICIPIO_ID)))
                    .isInstanceOf(CredenciaisInvalidasException.class);

            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            bcrypt.verify(() -> BCrypt.checkpw(eq(SENHA), hash.capture()));
            assertThat(hash.getValue()).startsWith("$2a$10$").hasSize(60);
        }
    }

    @Test
    @DisplayName("o tenant e aplicado antes da busca do usuario")
    void tenantEAplicadoAntesDaBusca() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario(PerfilUsuario.GESTOR, true)));

        servico.autenticar(new LoginRequest(EMAIL, SENHA, MUNICIPIO_ID));

        InOrder ordem = inOrder(entityManager, consultaDoTenant, usuarioRepository);
        ordem.verify(entityManager).createNativeQuery(SQL_DO_TENANT);
        ordem.verify(consultaDoTenant).setParameter(1, MUNICIPIO_ID.toString());
        ordem.verify(consultaDoTenant).getSingleResult();
        ordem.verify(usuarioRepository).findByEmail(EMAIL);
    }

    @Test
    @DisplayName("o email e normalizado com trim e minusculas antes da busca")
    void emailENormalizadoAntesDaBusca() {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario(PerfilUsuario.GESTOR, true)));

        LoginResponse resposta = servico.autenticar(new LoginRequest("  Gestora@Prefeitura.GOV.br ", SENHA, MUNICIPIO_ID));

        assertThat(claimsDo(resposta.token()).getLong("municipioId")).isEqualTo(MUNICIPIO_ID);
        verify(usuarioRepository, never()).findByEmail("  Gestora@Prefeitura.GOV.br ");
    }

    @Test
    @DisplayName("falha de login registra so o municipioId e o motivo generico, sem email nem senha")
    void falhaDeLoginNaoRegistraDadoSensivel(CapturedOutput saida) {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario(PerfilUsuario.GESTOR, true)));

        assertThatThrownBy(() -> servico.autenticar(new LoginRequest(EMAIL, "senha-errada-xyz", MUNICIPIO_ID)))
                .isInstanceOf(CredenciaisInvalidasException.class);

        String linhaDeFalha = linhasDoServico(saida).stream()
                .filter(linha -> linha.contains("municipio " + MUNICIPIO_ID))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhum log de falha com o municipioId"));
        assertThat(linhaDeFalha).contains(MOTIVO_NO_LOG);
        assertThat(saida.getAll()).doesNotContain(EMAIL, "senha-errada-xyz", SENHA);
    }

    @Test
    @DisplayName("login bem-sucedido nao registra email, senha nem token")
    void loginBemSucedidoNaoRegistraDadoSensivel(CapturedOutput saida) {
        when(usuarioRepository.findByEmail(EMAIL)).thenReturn(Optional.of(usuario(PerfilUsuario.GESTOR, true)));

        LoginResponse resposta = servico.autenticar(new LoginRequest(EMAIL, SENHA, MUNICIPIO_ID));

        assertThat(saida.getAll()).doesNotContain(EMAIL, SENHA, resposta.token());
    }

    private static List<String> linhasDoServico(CapturedOutput saida) {
        List<String> linhas = new ArrayList<>();
        for (String linha : saida.getAll().split("\\R")) {
            if (linha.contains(AutenticacaoServiceImpl.class.getSimpleName())) {
                linhas.add(linha);
            }
        }
        return linhas;
    }

    private static Usuario usuario(PerfilUsuario perfil, boolean ativo) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", USUARIO_ID);
        usuario.setMunicipioId(MUNICIPIO_ID);
        usuario.setEmail(EMAIL);
        usuario.setNome("Gestora");
        usuario.setSenhaHash(hashDaSenha);
        usuario.setPerfil(perfil);
        usuario.setAtivo(ativo);
        return usuario;
    }

    private static JSONObject claimsDo(String token) {
        return SaJwtUtil.getPayloads(token, "login", SEGREDO);
    }
}
