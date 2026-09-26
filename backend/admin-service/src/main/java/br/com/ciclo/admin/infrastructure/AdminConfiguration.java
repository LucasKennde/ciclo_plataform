package br.com.ciclo.admin.infrastructure;

import br.com.ciclo.admin.application.AdminApplicationService;
import br.com.ciclo.admin.application.AdminPorts.*;
import br.com.ciclo.shared.security.JwtRoleConverter;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AdminConfiguration {
  @Bean
  Declarables adminMessaging() {
    var exchange = new TopicExchange("ciclo.events", true, false);
    var queue = QueueBuilder.durable("admin.identity-audit").build();
    return new Declarables(
        exchange, queue, BindingBuilder.bind(queue).to(exchange).with("identity.admin.audit"));
  }

  @Bean
  AdminApplicationService adminApplicationService(
      Repository repository, Events events, IdentityClient identity, AiClient ai) {
    return new AdminApplicationService(repository, events, identity, ai);
  }

  @Bean
  JwtDecoder jwtDecoder(@Value("${app.jwt-secret}") String secret) {
    SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).build();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .hasRole("ADMIN"))
        .oauth2ResourceServer(
            oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(JwtRoleConverter.create())))
        .build();
  }
}
