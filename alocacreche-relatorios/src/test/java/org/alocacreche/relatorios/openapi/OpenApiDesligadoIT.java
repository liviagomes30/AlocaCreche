package org.alocacreche.relatorios.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
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
import org.testcontainers.utility.DockerImageName;

/**
 * Com {@code alocacreche.swagger.habilitado=false} (o padrao do perfil prod) nada do Swagger fica
 * acessivel nem liberado: o springdoc nao registra os documentos e o interceptor nao abre excecao,
 * entao o container responde 401 (nega por padrao: o caminho cai no tratador de recursos estaticos,
 * que tambem passa pelo interceptor).
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "alocacreche.swagger.habilitado=false")
@DisplayName("Swagger desligado no MSRelatorios: documento e UI nao respondem e nada fica liberado")
class OpenApiDesligadoIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withInitScript("db/relatorios-app-role.sql");

    @Container
    static RedisContainer redis =
            new RedisContainer(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "alocacreche_app");
        registry.add("spring.datasource.password", () -> "alocacreche_app");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /v3/api-docs, /swagger-ui.html e /swagger-ui/index.html respondem 401 sem token")
    void documentosNaoRespondemNemSaoLiberados() {
        for (String caminho : new String[] {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            ResponseEntity<String> resposta = restTemplate.getForEntity(caminho, String.class);

            assertThat(resposta.getStatusCode()).as(caminho).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("rota de negocio sem token continua 401")
    void rotaDeNegocioContinua401() {
        ResponseEntity<String> resposta = restTemplate.getForEntity(
                "/api/v1/relatorios/ocupacao?municipioId=1", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
