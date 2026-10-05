package org.alocacreche.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * Spring Cloud Gateway casa rotas na ordem declarada em application.yml - a primeira rota cujo
 * predicate bate com o path vence. O predicate de alocacreche-relatorios (/api/v1/relatorios/**) e
 * um subconjunto do predicate de alocacreche-core (/api/v1/**): se a rota do core vier primeiro,
 * toda chamada a relatorios seria roteada (silenciosamente) para o core. Este teste prende a ordem
 * correta como regressao.
 */
@SpringBootTest
class GatewayRoutingOrderTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void rotaDeRelatoriosVemAntesDaRotaDoCorePorSerMaisEspecifica() {
        List<RouteDefinition> rotas = routeDefinitionLocator.getRouteDefinitions().collectList().block();

        assertThat(rotas)
                .extracting(RouteDefinition::getId)
                .containsExactly("alocacreche-relatorios", "alocacreche-core");
    }

    @Test
    void loginCasaSomenteComARotaDoCore() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login"));

        List<String> rotasQueCasam = routeLocator
                .getRoutes()
                .filterWhen(rota -> rota.getPredicate().apply(exchange))
                .map(Route::getId)
                .collectList()
                .block();

        assertThat(rotasQueCasam).containsExactly("alocacreche-core");
    }
}
