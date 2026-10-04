package org.alocacreche.core.comum.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.secure.BCrypt;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova que a busca de usuario respeita a RLS (AUTH-06): um usuario de outro municipio nunca e
 * encontrado e o email e unico apenas dentro do mesmo municipio.
 */
@SpringBootTest
@Testcontainers
@DisplayName("UsuarioRepository com isolamento por municipio")
class UsuarioRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    private static final String HASH_SENHA = BCrypt.hashpw("senha-de-teste");

    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("findByEmail encontra o usuario do municipio corrente")
    void findByEmailEncontraUsuarioDoMunicipioCorrente() {
        criarUsuario(1L, "gestora.um@prefeitura.gov.br", "Gestora Um");

        Optional<Usuario> encontrado = buscarComoTenant(1L, "gestora.um@prefeitura.gov.br");

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getNome()).isEqualTo("Gestora Um");
        assertThat(encontrado.get().getMunicipioId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("sob o tenant 2, o usuario do municipio 1 nao e encontrado")
    void usuarioDeOutroMunicipioNaoEEncontrado() {
        criarUsuario(1L, "atendente.um@prefeitura.gov.br", "Atendente Um");

        Optional<Usuario> encontrado = buscarComoTenant(2L, "atendente.um@prefeitura.gov.br");

        assertThat(encontrado).isEmpty();
    }

    @Test
    @DisplayName("dois municipios aceitam o mesmo email e cada um enxerga so o seu")
    void doisMunicipiosAceitamOMesmoEmail() {
        criarUsuario(10L, "mesmo.email@prefeitura.gov.br", "Usuario do Municipio 10");
        criarUsuario(20L, "mesmo.email@prefeitura.gov.br", "Usuario do Municipio 20");

        assertThat(buscarComoTenant(10L, "mesmo.email@prefeitura.gov.br"))
                .get().extracting(Usuario::getNome).isEqualTo("Usuario do Municipio 10");
        assertThat(buscarComoTenant(20L, "mesmo.email@prefeitura.gov.br"))
                .get().extracting(Usuario::getNome).isEqualTo("Usuario do Municipio 20");
    }

    @Test
    @DisplayName("o mesmo municipio rejeita um email repetido")
    void mesmoMunicipioRejeitaEmailRepetido() {
        criarUsuario(30L, "repetido@prefeitura.gov.br", "Primeiro");

        assertThatThrownBy(() -> criarUsuario(30L, "repetido@prefeitura.gov.br", "Segundo"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Optional<Usuario> buscarComoTenant(Long municipioId, String email) {
        return transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            return usuarioRepository.findByEmail(email);
        });
    }

    private void criarUsuario(Long municipioId, String email, String nome) {
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, municipioId);
            Usuario usuario = new Usuario();
            usuario.setMunicipioId(municipioId);
            usuario.setEmail(email);
            usuario.setNome(nome);
            usuario.setSenhaHash(HASH_SENHA);
            usuario.setPerfil(PerfilUsuario.GESTOR);
            usuario.setAtivo(true);
            usuarioRepository.saveAndFlush(usuario);
        });
    }
}
