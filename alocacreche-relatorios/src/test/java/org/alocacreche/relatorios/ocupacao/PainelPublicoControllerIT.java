package org.alocacreche.relatorios.ocupacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.redis.testcontainers.RedisContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.alocacreche.relatorios.seguranca.TokenTeste;
import org.alocacreche.relatorios.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Cobre PAINEL-01, PAINEL-02, PAINEL-03 via HTTP real. A lógica de cache/fail-open já foi provada em
 * PainelPublicoServiceIT/PainelPublicoFailOpenIT - aqui o foco é só a camada HTTP (roteamento,
 * validação de query param, serialização JSON da resposta). O painel é aberto por decisão
 * (AUTH-16): responde 200 sem token e também com um token válido.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PainelPublicoControllerIT {

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
    @MockitoSpyBean private OcupacaoTurmaRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void limparToken() {
        TokenTeste.usarToken(restTemplate, null);
    }

    @Test
    void consultarRetornaPainelAgregadoDoMunicipio() {
        seedTurma(701L, 7001L, 70L, 4, 1L);
        seedTurma(701L, 7002L, 71L, 6, 1L);

        ResponseEntity<PainelPublicoDTO> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/painel-publico?municipioId=701", PainelPublicoDTO.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody().totalVagasDisponiveis()).isEqualTo(10L);
        assertThat(resposta.getBody().totalCrechesComVaga()).isEqualTo(2L);
        assertThat(resposta.getBody().totalTurmas()).isEqualTo(2L);
    }

    @Test
    void consultarSemMunicipioIdRetorna400() {
        ResponseEntity<String> resposta =
                restTemplate.getForEntity("/api/v1/relatorios/painel-publico", String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        // Sinal decisivo: nao so o status - prova que a requisicao nunca chegou a tocar o
        // repositorio (nem banco, nem a consulta que alimentaria o cache).
        verify(repository, never()).buscarPorFiltro(any(), any(), any());
    }

    @Test
    void consultarComMunicipioIdNaoNumericoRetorna400() {
        ResponseEntity<String> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/painel-publico?municipioId=abc", String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(400);
        verify(repository, never()).buscarPorFiltro(any(), any(), any());
    }

    @Test
    void consultarMunicipioSemDadosRetornaTotaisZerados() {
        ResponseEntity<PainelPublicoDTO> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/painel-publico?municipioId=799", PainelPublicoDTO.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody().totalVagasDisponiveis()).isZero();
        assertThat(resposta.getBody().totalCrechesComVaga()).isZero();
        assertThat(resposta.getBody().totalTurmas()).isZero();
        assertThat(resposta.getBody().atualizadoEm()).isNull();
    }

    @Test
    void consultarComTokenValidoSemPermissaoNenhumaRetorna200() {
        seedTurma(702L, 7003L, 72L, 3, 1L);
        TokenTeste.usarToken(restTemplate, TokenTeste.valido(1L, List.of()));

        ResponseEntity<PainelPublicoDTO> resposta =
                restTemplate.getForEntity(
                        "/api/v1/relatorios/painel-publico?municipioId=702", PainelPublicoDTO.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody().totalVagasDisponiveis()).isEqualTo(3L);
    }

    private void seedTurma(
            Long municipioId, Long turmaId, Long crecheId, Integer vagas, Long eventId) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            TenantContext.aplicar(entityManager, municipioId);
                            repository.upsert(turmaId, municipioId, crecheId, vagas, eventId);
                        });
    }
}
