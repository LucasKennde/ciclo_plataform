package br.com.ciclo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class GatewayServiceApplicationTest {
  @Autowired RouteDefinitionLocator routes;

  @Test
  void loadsAllConfiguredRoutes() {
    assertThat(routes.getRouteDefinitions().map(route -> route.getId()).collectList().block())
        .containsExactlyInAnyOrder("identity-auth", "study", "ai-admin", "admin");
  }
}
