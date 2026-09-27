package org.alocacreche.core.comum.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.CrecheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova a garantia real de isolamento multi-tenant (ADR 0001, decisao 14): nao e o
 * {@code findByMunicipioId} do repositorio quem protege, e a policy de Row-Level Security do
 * Postgres com {@code FORCE}, aplicada em {@code schema.sql}. Usa {@link Creche} como entidade de
 * prova por ser a mais simples tenant-scoped (so extende {@link
 * org.alocacreche.core.comum.EntidadeMultiTenant}, sem relacionamento de saida).
 *
 * <p>Conecta como a role nao-superuser {@code alocacreche_app} (criada por
 * {@code db/core-app-role.sql}), nunca como o usuario bootstrap do container - superusers ignoram
 * RLS incondicionalmente, mesmo com FORCE ROW LEVEL SECURITY (ver AD-002).
 */
@SpringBootTest
@Testcontainers
@DisplayName("Isolamento multi-tenant via Row-Level Security")
class TenantIsolationIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private CrecheRepository crecheRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("sem TenantContext.aplicar, a query falha fechada em vez de vazar dado")
    void semTenantContextAplicadoFalhaFechadoEmVezDeVazarDados() {
        criarCrecheComoTenant(1L, "Creche do Municipio 1");

        // Fail-closed: sem TenantContext.aplicar, a query sempre lanca excecao - nunca retorna a
        // linha nem uma lista vazia silenciosa. A mensagem exata do Postgres varia com o estado da
        // conexao do pool ("unrecognized configuration parameter" numa conexao nunca usada com esse
        // GUC; "invalid input syntax for type bigint: ''" numa conexao que ja teve o GUC referenciado
        // antes, mesmo sem valor no momento) - por isso a asserção é sobre o tipo (falha), nao a
        // string exata da mensagem.
        assertThatThrownBy(
                        () -> transactionTemplate.executeWithoutResult(
                                status -> crecheRepository.findAll()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("RLS esconde linha de outro municipio mesmo com FORCE ROW LEVEL SECURITY")
    void rlsEscondeLinhaDeOutroMunicipio() {
        Long crecheId = criarCrecheComoTenant(1L, "Creche do Municipio 1");

        Creche encontradaComoTenantErrado = transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, 2L);
            return entityManager.find(Creche.class, crecheId);
        });

        assertThat(encontradaComoTenantErrado).isNull();
    }

    @Test
    @DisplayName("mesmo tenant enxerga a propria linha normalmente")
    void mesmoTenantEnxergaAPropriaLinha() {
        Long crecheId = criarCrecheComoTenant(1L, "Creche do Municipio 1");

        Creche encontrada = transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, 1L);
            return entityManager.find(Creche.class, crecheId);
        });

        assertThat(encontrada).isNotNull();
        assertThat(encontrada.getNome()).isEqualTo("Creche do Municipio 1");
    }

    @Test
    @DisplayName("findAll do repositorio so retorna linhas do tenant aplicado, mesmo sem filtro no metodo")
    void findAllSoRetornaLinhasDoTenantAplicado() {
        criarCrecheComoTenant(10L, "Creche do Municipio 10");
        criarCrecheComoTenant(20L, "Creche do Municipio 20");

        List<Creche> crechesDoTenant10 = transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, 10L);
            return crecheRepository.findAll();
        });

        assertThat(crechesDoTenant10).extracting(Creche::getNome).containsExactly("Creche do Municipio 10");
    }

    private Long criarCrecheComoTenant(Long municipioId, String nome) {
        return transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, municipioId);

            Creche creche = new Creche();
            creche.setMunicipioId(municipioId);
            creche.setNome(nome);
            creche.setCapacidadeTotal(20);
            Endereco endereco = new Endereco();
            endereco.setCidade("Sao Paulo");
            endereco.setEstado("SP");
            endereco.setLatitude(-23.55);
            endereco.setLongitude(-46.63);
            creche.setEndereco(endereco);
            return crecheRepository.save(creche).getId();
        });
    }
}
