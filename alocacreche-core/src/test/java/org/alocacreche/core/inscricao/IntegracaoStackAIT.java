package org.alocacreche.core.inscricao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova a convivencia, no mesmo contexto Spring, do InscricaoController da Stack A (pacote
 * inscricao, sem permissao propria) com o InscricaoController desta entrega (pacote
 * inscricao.controller): os dois sobem e a rota da Stack A continua protegida pela negacao por
 * padrao do Sa-Token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Integracao da Stack A: os dois controllers de inscricao no mesmo contexto")
class IntegracaoStackAIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/core-app-role.sql");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
    }

    @Autowired private ApplicationContext contexto;
    @Autowired private TestRestTemplate restTemplate;

    @Test
    @DisplayName("os dois controllers e os dois servicos de inscricao existem como beans distintos")
    void osDoisControllersEOsDoisServicosExistem() {
        assertThat(contexto.getBean("inscricaoController"))
                .isInstanceOf(org.alocacreche.core.inscricao.InscricaoController.class);
        assertThat(contexto.getBean("inscricaoApiController"))
                .isInstanceOf(org.alocacreche.core.inscricao.controller.InscricaoController.class);
        assertThat(contexto.getBean("inscricaoService"))
                .isInstanceOf(org.alocacreche.core.inscricao.InscricaoService.class);
        assertThat(contexto.getBean("inscricaoServiceImpl"))
                .isInstanceOf(org.alocacreche.core.inscricao.service.InscricaoServiceImpl.class);
    }

    @Test
    @DisplayName("a rota da Stack A sem token responde 401 (negacao por padrao)")
    void aRotaDaStackASemTokenResponde401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resposta =
                restTemplate.exchange(
                        "/inscricoes", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
