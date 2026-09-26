package br.com.ciclo.gateway;

import br.com.ciclo.shared.events.EventEnvelope;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.http.server.reactive.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
public class GatewayFilters implements GlobalFilter, Ordered {
  private final AtomicBoolean maintenance = new AtomicBoolean(false);
  private static final Set<HttpMethod> SAFE =
      Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

  public int getOrder() {
    return -100;
  }

  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    var request = exchange.getRequest();
    String path = request.getURI().getPath();
    if (maintenance.get() && path.startsWith("/api/v1") && !path.startsWith("/api/v1/auth")) {
      exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
      return exchange.getResponse().setComplete();
    }
    if (!SAFE.contains(request.getMethod())
        && !path.matches(
            "/api/v1/auth/(login|register|refresh|forgot-password|reset-password|verify-email)")) {
      var cookie = request.getCookies().getFirst("XSRF-TOKEN");
      String header = request.getHeaders().getFirst("X-XSRF-TOKEN");
      if (cookie == null || !cookie.getValue().equals(header)) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
      }
    }
    var builder = request.mutate();
    String correlation =
        Optional.ofNullable(request.getHeaders().getFirst("x-correlation-id"))
            .orElse(UUID.randomUUID().toString());
    builder.header("x-correlation-id", correlation);
    var access = request.getCookies().getFirst("ciclo_access");
    if (access != null && request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) == null)
      builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + access.getValue());
    if (request.getCookies().getFirst("XSRF-TOKEN") == null)
      exchange
          .getResponse()
          .addCookie(
              ResponseCookie.from("XSRF-TOKEN", UUID.randomUUID().toString())
                  .httpOnly(false)
                  .secure(false)
                  .sameSite("Lax")
                  .path("/")
                  .build());
    return chain.filter(exchange.mutate().request(builder.build()).build());
  }

  @RabbitListener(queues = "gateway.platform-settings")
  public void settings(EventEnvelope event) {
    Object value = event.payload().get("maintenanceMode");
    maintenance.set(Boolean.parseBoolean(String.valueOf(value)));
  }
}
