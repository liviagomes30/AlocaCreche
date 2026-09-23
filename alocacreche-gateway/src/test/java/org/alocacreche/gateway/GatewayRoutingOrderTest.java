package org.alocacreche.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

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

    @Test
    void rotaDeRelatoriosVemAntesDaRotaDoCorePorSerMaisEspecifica() {
        List<RouteDefinition> rotas = routeDefinitionLocator.getRouteDefinitions().collectList().block();

        assertThat(rotas)
                .extracting(RouteDefinition::getId)
                .containsExactly("alocacreche-relatorios", "alocacreche-core");
    }
}
