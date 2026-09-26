package br.com.ciclo.ai.infrastructure.config;

import br.com.ciclo.ai.application.*;
import br.com.ciclo.ai.application.AiPorts.*;
import br.com.ciclo.ai.domain.AiCatalog.Provider;
import br.com.ciclo.shared.security.JwtRoleConverter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class AiConfiguration {
  @Bean
  AiApplicationService aiApplicationService(
      Repository repo,
      Cipher cipher,
      ProviderGateway providers,
      Counters counters,
      AiPorts.Objects objects,
      Events events,
      @Value("${app.providers.openai-key:}") String openai,
      @Value("${app.providers.anthropic-key:}") String anthropic,
      @Value("${app.providers.gemini-key:}") String gemini) {
    Map<Provider, String> keys = new EnumMap<>(Provider.class);
    if (!openai.isBlank()) keys.put(Provider.OPENAI, openai);
    if (!anthropic.isBlank()) keys.put(Provider.ANTHROPIC, anthropic);
    if (!gemini.isBlank()) keys.put(Provider.GEMINI, gemini);
    return new AiApplicationService(repo, cipher, providers, counters, objects, events, keys);
  }

  @Bean
  S3Client s3(
      @Value("${app.s3.endpoint}") String endpoint,
      @Value("${app.s3.access-key}") String access,
      @Value("${app.s3.secret-key}") String secret) {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
        .build();
  }

  @Bean
  Declarables aiMessaging() {
    var exchange = new TopicExchange("ciclo.events", true, false);
    var queue =
        QueueBuilder.durable("ai.requests")
            .withArgument("x-dead-letter-exchange", "ciclo.dlx")
            .build();
    var dlx = new DirectExchange("ciclo.dlx", true, false);
    var dlq = QueueBuilder.durable("ai.requests.dlq").build();
    return new Declarables(
        exchange,
        queue,
        BindingBuilder.bind(queue).to(exchange).with("ai.execution.requested"),
        dlx,
        dlq,
        BindingBuilder.bind(dlq).to(dlx).with("ai.requests"));
  }

  @Bean
  JwtDecoder jwtDecoder(@Value("${app.jwt-secret}") String secret) {
    SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).build();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(JwtRoleConverter.create())))
        .build();
  }
}
