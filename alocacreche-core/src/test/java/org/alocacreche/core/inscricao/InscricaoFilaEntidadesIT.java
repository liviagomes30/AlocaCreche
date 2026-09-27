package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.alocacreche.core.comum.Endereco;
import org.alocacreche.core.comum.tenant.TenantContext;
import org.alocacreche.core.matricula.Creche;
import org.alocacreche.core.matricula.CrecheRepository;
import org.alocacreche.core.matricula.FaixaEtaria;
import org.alocacreche.core.matricula.Turma;
import org.alocacreche.core.matricula.TurmaRepository;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova que o mapeamento JPA da base de Inscricao & Fila (entidades, embeddable, referencia
 * direta a Municipio) persiste e recarrega corretamente num Postgres real, antes de qualquer
 * regra de negocio ser implementada em cima. Turma/Creche aqui sao so dado de apoio do teste
 * (FilaEspera referencia Turma por ID cru, nao por objeto - ver comentario em FilaEspera.java).
 * Ver docs/adr/0004-divisao-equipe-tres-integrantes.md.
 *
 * <p>Conecta como a role nao-superuser {@code alocacreche_app} (criada por
 * {@code db/core-app-role.sql}), nunca como o usuario bootstrap do container - superusers ignoram
 * RLS incondicionalmente, mesmo com FORCE ROW LEVEL SECURITY (ver AD-002 /
 * {@link org.alocacreche.core.comum.EntidadeMultiTenant}). Por isso todo teste chama
 * {@link TenantContext#aplicar} antes de tocar uma tabela tenant-scoped.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Base JPA de Inscricao & Fila")
class InscricaoFilaEntidadesIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private ResponsavelRepository responsavelRepository;
    @Autowired private CriancaRepository criancaRepository;
    @Autowired private InscricaoRepository inscricaoRepository;
    @Autowired private FilaEsperaRepository filaEsperaRepository;
    @Autowired private MunicipioRepository municipioRepository;
    @Autowired private CrecheRepository crecheRepository;
    @Autowired private TurmaRepository turmaRepository;

    @PersistenceContext private EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("Crianca resolve Responsavel e o Endereco embutido dele")
    void criancaResolveResponsavelEEnderecoEmbutido() {
        Municipio municipio = criarMunicipioSalvo();
        TenantContext.aplicar(entityManager, municipio.getId());
        Responsavel responsavel = criarResponsavelSalvo(municipio, "Maria Teste");
        Crianca crianca = criarCriancaSalva(municipio, responsavel, "Joaozinho");

        entityManager.flush();
        entityManager.clear();

        Crianca recarregada = criancaRepository.findById(crianca.getId()).orElseThrow();
        assertThat(recarregada.getResponsavel().getNome()).isEqualTo("Maria Teste");
        assertThat(recarregada.getResponsavel().getEndereco().getCidade()).isEqualTo("Sao Paulo");
    }

    @Test
    @Transactional
    @DisplayName("Inscricao resolve Municipio e a ConfiguracaoPriorizacao embutida nele")
    void inscricaoResolveMunicipioEConfiguracaoPriorizacaoEmbutida() {
        Municipio municipio = criarMunicipioSalvo();
        TenantContext.aplicar(entityManager, municipio.getId());
        Responsavel responsavel = criarResponsavelSalvo(municipio, "Maria Teste");
        Crianca crianca = criarCriancaSalva(municipio, responsavel, "Joaozinho");

        Inscricao inscricao = new Inscricao();
        inscricao.setMunicipio(municipio);
        inscricao.setCrianca(crianca);
        inscricao.setRendaPerCapita(new BigDecimal("350.00"));
        inscricao.setCriterioVulnerabilidade(Boolean.TRUE);
        inscricao = inscricaoRepository.save(inscricao);

        entityManager.flush();
        entityManager.clear();

        Inscricao recarregada = inscricaoRepository.findById(inscricao.getId()).orElseThrow();
        assertThat(recarregada.getMunicipio().getConfiguracaoPriorizacao().getPesoRenda())
                .isEqualTo(0.4);
        assertThat(recarregada.getCriterioVulnerabilidade()).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("FilaEspera resolve Inscricao por objeto e Turma por id cru")
    void filaEsperaResolveInscricaoPorObjetoETurmaPorIdCru() {
        Municipio municipio = criarMunicipioSalvo();
        TenantContext.aplicar(entityManager, municipio.getId());
        Responsavel responsavel = criarResponsavelSalvo(municipio, "Maria Teste");
        Crianca crianca = criarCriancaSalva(municipio, responsavel, "Joaozinho");

        Inscricao inscricao = new Inscricao();
        inscricao.setMunicipio(municipio);
        inscricao.setCrianca(crianca);
        inscricao = inscricaoRepository.save(inscricao);

        Turma turma = criarTurmaSalva(municipio, "Creche Central");

        FilaEspera filaEspera = new FilaEspera();
        filaEspera.setMunicipioId(municipio.getId());
        filaEspera.setInscricao(inscricao);
        filaEspera.setTurmaId(turma.getId());
        filaEspera.setPosicao(1);
        filaEspera = filaEsperaRepository.save(filaEspera);

        entityManager.flush();
        entityManager.clear();

        FilaEspera recarregada = filaEsperaRepository.findById(filaEspera.getId()).orElseThrow();
        assertThat(recarregada.getInscricao().getCrianca().getResponsavel().getNome())
                .isEqualTo("Maria Teste");

        Turma turmaRecarregada = turmaRepository.findById(recarregada.getTurmaId()).orElseThrow();
        assertThat(turmaRecarregada.getCreche().getNome()).isEqualTo("Creche Central");
    }

    private Municipio criarMunicipioSalvo() {
        Municipio municipio = new Municipio();
        municipio.setNome("Sao Paulo");
        municipio.setUf("SP");
        municipio.setPrazoConfirmacaoDias(5);
        municipio.setConfiguracaoPriorizacao(
                ConfiguracaoPriorizacao.builder()
                        .pesoRenda(0.4)
                        .pesoVulnerabilidade(0.3)
                        .pesoDistancia(0.2)
                        .pesoIrmaoMatriculado(0.1)
                        .build());
        return municipioRepository.save(municipio);
    }

    private Responsavel criarResponsavelSalvo(Municipio municipio, String nome) {
        Responsavel responsavel = new Responsavel();
        responsavel.setMunicipioId(municipio.getId());
        responsavel.setNome(nome);
        responsavel.setCpf("12345678900");
        Endereco endereco = new Endereco();
        endereco.setCidade("Sao Paulo");
        endereco.setEstado("SP");
        endereco.setLatitude(-23.5505);
        endereco.setLongitude(-46.6333);
        responsavel.setEndereco(endereco);
        return responsavelRepository.save(responsavel);
    }

    private Crianca criarCriancaSalva(Municipio municipio, Responsavel responsavel, String nome) {
        Crianca crianca = new Crianca();
        crianca.setMunicipioId(municipio.getId());
        crianca.setResponsavel(responsavel);
        crianca.setNome(nome);
        crianca.setDataNascimento(LocalDate.of(2023, 3, 10));
        return criancaRepository.save(crianca);
    }

    private Turma criarTurmaSalva(Municipio municipio, String nomeCreche) {
        Creche creche = new Creche();
        creche.setMunicipioId(municipio.getId());
        creche.setNome(nomeCreche);
        creche.setCapacidadeTotal(30);
        Endereco endereco = new Endereco();
        endereco.setCidade("Sao Paulo");
        endereco.setEstado("SP");
        endereco.setLatitude(-23.5505);
        endereco.setLongitude(-46.6333);
        creche.setEndereco(endereco);
        creche = crecheRepository.save(creche);

        Turma turma = new Turma();
        turma.setMunicipioId(municipio.getId());
        turma.setCreche(creche);
        turma.setFaixaEtaria(FaixaEtaria.BERCARIO);
        turma.setVagasTotais(10);
        turma.setVagasDisponiveis(10);
        return turmaRepository.save(turma);
    }
}
