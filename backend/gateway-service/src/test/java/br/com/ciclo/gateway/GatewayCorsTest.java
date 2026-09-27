package br.com.ciclo.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class GatewayCorsTest {
  @Autowired ApplicationContext context;

  private WebTestClient client() {
    return WebTestClient.bindToApplicationContext(context).build();
  }

  @Test
  void treatsHttpsOriginAsSameOriginWhenProxyForwardsTheHttpsScheme() {
    client()
        .get()
        .uri("http://app.example.com/no-such-route")
        .header("Origin", "https://app.example.com")
        .header("X-Forwarded-Proto", "https")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void stillRejectsForeignOriginsWhenTheSchemeIsForwarded() {
    client()
        .get()
        .uri("http://app.example.com/no-such-route")
        .header("Origin", "https://evil.example")
        .header("X-Forwarded-Proto", "https")
        .exchange()
        .expectStatus()
        .isForbidden();
  }
}
