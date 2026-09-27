package org.alocacreche.core.matricula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova que o mapeamento JPA da base de Matricula & Vagas/Creches persiste corretamente e que o
 * lock otimista de {@link Turma} (via {@code @Version}) de fato impede uma escrita concorrente
 * com dado desatualizado - nao so que a versao incrementa em sequencia. Essa e a garantia central
 * do projeto (ADR 0001, decisao 1): duas criancas nunca ficam com a mesma vaga.
 *
 * <p>Conecta como a role nao-superuser {@code alocacreche_app} (criada por
 * {@code db/core-app-role.sql}) e chama {@link TenantContext#aplicar} no inicio de cada
 * transacao - {@code creche}/{@code turma} tem Row-Level Security com {@code FORCE}, entao nem o
 * dono da tabela escapa dela (ver AD-002 / {@link org.alocacreche.core.comum.EntidadeMultiTenant}).
 * {@code TransactionTemplate.execute(...)} abre uma transacao nova por chamada - por isso o tenant
 * precisa ser (re)aplicado em toda lambda, nao so uma vez no teste.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Base JPA de Matricula & Vagas/Creches")
class MatriculaVagasEntidadesIT {

    private static final Long MUNICIPIO_ID = 1L;

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
    @Autowired private TurmaRepository turmaRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("version incrementa a cada update bem sucedido")
    void versionIncrementaACadaUpdateBemSucedido() {
        Long turmaId = transactionTemplate.execute(status -> criarTurmaSalva(15).getId());

        Long versaoInicial = transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, MUNICIPIO_ID);
            return turmaRepository.findById(turmaId).orElseThrow().getVersion();
        });
        assertThat(versaoInicial).isNotNull();

        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, MUNICIPIO_ID);
            Turma turma = turmaRepository.findById(turmaId).orElseThrow();
            turma.setVagasDisponiveis(14);
            turmaRepository.saveAndFlush(turma);
        });

        Long versaoFinal = transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, MUNICIPIO_ID);
            return turmaRepository.findById(turmaId).orElseThrow().getVersion();
        });
        assertThat(versaoFinal).isGreaterThan(versaoInicial);
    }

    @Test
    @DisplayName("salvar uma copia com version desatualizada falha em vez de sobrescrever silenciosamente")
    void salvarComVersionDesatualizadaFalhaEmVezDeSobrescreverSilenciosamente() {
        Long turmaId = transactionTemplate.execute(status -> criarTurmaSalva(10).getId());

        // sessao A le o estado inicial e guarda uma copia que nunca mais e re-lida - "esquece" o
        // que a sessao B vai fazer, exatamente como duas requisicoes concorrentes reais.
        Turma copiaDesatualizada = transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, MUNICIPIO_ID);
            return turmaRepository.findById(turmaId).orElseThrow();
        });

        // sessao B le de novo, aloca uma vaga e commita primeiro.
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.aplicar(entityManager, MUNICIPIO_ID);
            Turma turmaAtual = turmaRepository.findById(turmaId).orElseThrow();
            turmaAtual.setVagasDisponiveis(9);
            turmaRepository.saveAndFlush(turmaAtual);
        });

        // sessao A tenta commitar em cima do estado que ja nao existe mais - precisa falhar,
        // nunca decrementar vagasDisponiveis por cima do que a sessao B ja alocou.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    TenantContext.aplicar(entityManager, MUNICIPIO_ID);
                    copiaDesatualizada.setVagasDisponiveis(8);
                    turmaRepository.saveAndFlush(copiaDesatualizada);
                }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Integer vagasFinais = transactionTemplate.execute(status -> {
            TenantContext.aplicar(entityManager, MUNICIPIO_ID);
            return turmaRepository.findById(turmaId).orElseThrow().getVagasDisponiveis();
        });
        assertThat(vagasFinais).isEqualTo(9);
    }

    private Turma criarTurmaSalva(int vagasIniciais) {
        TenantContext.aplicar(entityManager, MUNICIPIO_ID);

        Creche creche = new Creche();
        creche.setMunicipioId(MUNICIPIO_ID);
        creche.setNome("Creche do Bairro");
        creche.setCapacidadeTotal(20);
        Endereco endereco = new Endereco();
        endereco.setCidade("Sao Paulo");
        endereco.setEstado("SP");
        endereco.setLatitude(-23.55);
        endereco.setLongitude(-46.63);
        creche.setEndereco(endereco);
        creche = crecheRepository.save(creche);

        Turma turma = new Turma();
        turma.setMunicipioId(MUNICIPIO_ID);
        turma.setCreche(creche);
        turma.setFaixaEtaria(FaixaEtaria.MATERNAL);
        turma.setVagasTotais(vagasIniciais);
        turma.setVagasDisponiveis(vagasIniciais);
        return turmaRepository.saveAndFlush(turma);
    }
}
