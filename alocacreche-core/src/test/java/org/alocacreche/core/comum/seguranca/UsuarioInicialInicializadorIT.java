package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.secure.BCrypt;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova a criacao do ADMIN inicial por configuracao (AUTH-15) com Postgres real. O inicializador
 * e instanciado a mao com propriedades diferentes por teste; o contexto Spring sobe sem nenhuma
 * propriedade de usuario inicial.
 */
@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Usuario ADMIN inicial por configuracao")
class UsuarioInicialInicializadorIT {

    private static final String EMAIL = "admin.inicial@prefeitura.gov.br";
    private static final String SENHA = "SenhaInicial#2026";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private MunicipioRepository municipioRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Environment environment;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("com as propriedades e o municipio existente, cria o ADMIN com a senha em BCrypt")
    void criaAdminComSenhaEmBcrypt() {
        Long municipioId = criarMunicipio();

        inicializador(" Admin.Inicial@Prefeitura.gov.br ", SENHA, municipioId).run(null);

        Usuario criado = buscar(municipioId, EMAIL).orElseThrow();
        assertThat(criado.getPerfil()).isEqualTo(PerfilUsuario.ADMIN);
        assertThat(criado.isAtivo()).isTrue();
        assertThat(criado.getMunicipioId()).isEqualTo(municipioId);
        assertThat(criado.getSenhaHash()).isNotEqualTo(SENHA);
        assertThat(BCrypt.checkpw(SENHA, criado.getSenhaHash())).isTrue();
    }

    @Test
    @DisplayName("reiniciar nao cria outro usuario nem altera a senha existente")
    void reiniciarNaoCriaOutroNemAlteraASenha() {
        Long municipioId = criarMunicipio();
        inicializador(EMAIL, SENHA, municipioId).run(null);
        String hashOriginal = buscar(municipioId, EMAIL).orElseThrow().getSenhaHash();

        inicializador(EMAIL, "OutraSenha#9999", municipioId).run(null);

        assertThat(contar(municipioId)).isEqualTo(1);
        Usuario existente = buscar(municipioId, EMAIL).orElseThrow();
        assertThat(existente.getSenhaHash()).isEqualTo(hashOriginal);
        assertThat(BCrypt.checkpw(SENHA, existente.getSenhaHash())).isTrue();
    }

    @Test
    @DisplayName("municipio inexistente: registra aviso, nao falha e nao cria usuario")
    void municipioInexistenteRegistraAvisoENaoCriaUsuario(CapturedOutput saida) {
        Long municipioInexistente = 888_777L;

        inicializador(EMAIL, SENHA, municipioInexistente).run(null);

        assertThat(contar(municipioInexistente)).isZero();
        assertThat(saida.getAll())
                .contains("Usuario inicial nao criado: municipio " + municipioInexistente + " inexistente")
                .doesNotContain(EMAIL)
                .doesNotContain(SENHA);
    }

    @Test
    @DisplayName("sem as propriedades configuradas, nao cria nada")
    void semPropriedadesNaoCriaNada() {
        Long municipioId = criarMunicipio();

        inicializador(null, null, null).run(null);
        inicializador(EMAIL, null, municipioId).run(null);
        inicializador(EMAIL, SENHA, null).run(null);
        inicializador(" ", SENHA, municipioId).run(null);
        inicializador(EMAIL, " ", municipioId).run(null);

        assertThat(contar(municipioId)).isZero();
    }

    @Test
    @DisplayName("a senha do usuario inicial nao tem valor padrao na configuracao do repositorio")
    void senhaNaoTemValorPadrao() {
        assertThat(environment.getProperty("alocacreche.seguranca.usuario-inicial.senha")).isNull();
        assertThat(environment.getProperty("alocacreche.seguranca.usuario-inicial.email")).isNull();
        assertThat(environment.getProperty("alocacreche.seguranca.usuario-inicial.municipio-id")).isNull();
    }

    private UsuarioInicialInicializador inicializador(String email, String senha, Long municipioId) {
        return new UsuarioInicialInicializador(
                new UsuarioInicialProperties(email, senha, municipioId),
                municipioRepository,
                usuarioRepository,
                entityManager,
                transactionManager);
    }

    private Optional<Usuario> buscar(Long municipioId, String email) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            return usuarioRepository.findByEmail(email);
        });
    }

    private int contar(Long municipioId) {
        List<Usuario> todos = new TransactionTemplate(transactionManager).execute(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            return usuarioRepository.findAll();
        });
        return todos.size();
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
}
