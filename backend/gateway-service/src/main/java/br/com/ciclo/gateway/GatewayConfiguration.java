package br.com.ciclo.gateway;

import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class GatewayConfiguration {
  @Bean
  Declarables gatewayMessaging() {
    var exchange = new TopicExchange("ciclo.events", true, false);
    var queue = QueueBuilder.durable("gateway.platform-settings").build();
    return new Declarables(
        exchange, queue, BindingBuilder.bind(queue).to(exchange).with("platform.settings.changed"));
  }

  @Bean
  CorsWebFilter cors() {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:4200", "http://localhost:4201"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return new CorsWebFilter(source);
  }
}
