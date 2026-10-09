package org.alocacreche.core.comum.seguranca.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.alocacreche.core.comum.seguranca.config.UsuarioInicialProperties;
import org.alocacreche.core.comum.seguranca.dto.LoginResponse;
import org.alocacreche.core.notificacao.ConfiguracaoPriorizacao;
import org.alocacreche.core.notificacao.Municipio;
import org.alocacreche.core.notificacao.MunicipioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova o wiring do usuario ADMIN inicial no contexto Spring real (AUTH-15): o binding das
 * propriedades {@code alocacreche.seguranca.usuario-inicial.*}, o registro do
 * {@link UsuarioInicialInicializador} como bean e o login HTTP com a credencial configurada.
 * Complementa o {@link UsuarioInicialInicializadorIT}, que instancia o runner a mao.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "alocacreche.seguranca.usuario-inicial.email=admin.wiring@prefeitura.gov.br",
            "alocacreche.seguranca.usuario-inicial.senha=SenhaWiring#2026",
            "alocacreche.seguranca.usuario-inicial.municipio-id=1"
        })
@Testcontainers
@DisplayName("Wiring do usuario ADMIN inicial no startup")
class UsuarioInicialWiringIT {

    private static final String EMAIL = "admin.wiring@prefeitura.gov.br";
    private static final String SENHA = "SenhaWiring#2026";
    private static final long MUNICIPIO_ID = 1L;

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private UsuarioInicialInicializador inicializador;
    @Autowired private UsuarioInicialProperties propriedades;
    @Autowired private MunicipioRepository municipioRepository;
    @Autowired private TestRestTemplate restTemplate;

    @Test
    @DisplayName("propriedades ligadas e runner registrado: o ADMIN criado no startup faz login HTTP")
    void adminConfiguradoFazLoginHttp() {
        assertThat(propriedades.estaConfigurado()).isTrue();
        assertThat(municipioRepository.saveAndFlush(novoMunicipio()).getId()).isEqualTo(MUNICIPIO_ID);

        inicializador.run(null);

        ResponseEntity<JsonNode> resposta = restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("email", EMAIL, "senha", SENHA, "municipioId", MUNICIPIO_ID),
                JsonNode.class);
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody().get("tipo").asText()).isEqualTo(LoginResponse.TIPO_BEARER);
        assertThat(resposta.getBody().get("token").asText()).isNotBlank();
    }

    private Municipio novoMunicipio() {
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
        return municipio;
    }
}
