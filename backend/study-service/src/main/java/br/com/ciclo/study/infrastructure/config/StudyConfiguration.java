package br.com.ciclo.study.infrastructure.config;

import br.com.ciclo.shared.events.EventEnvelope;
import br.com.ciclo.shared.security.JwtRoleConverter;
import br.com.ciclo.study.application.*;
import br.com.ciclo.study.application.StudyPorts.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class StudyConfiguration {
  /**
   * Conteúdo programático oficial, transcrito do edital. Vive no repositório para que o edital não
   * dependa de credencial de IA nem da variação de um modelo entre execuções.
   */
  @Bean
  ProgramaCatalog programaCatalog(
      JsonMapper json, @Value("${app.programas.catalog:programas/seduc-2026.json}") String path) {
    return new ProgramaCatalog(json, path);
  }

  @Bean
  StudyApplicationService studyApplicationService(
      Competitions competitions,
      Store store,
      Onboardings onboardings,
      StudyPorts.Objects objects,
      Events events,
      ProgramaCatalog catalog) {
    return new StudyApplicationService(competitions, store, onboardings, objects, events, catalog);
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
  // Boot's default RabbitTemplate/@RabbitListener converter only accepts String, byte[] and
  // Serializable; EventEnvelope is a plain record, so publish/consume fail without this.
  MessageConverter rabbitMessageConverter(JsonMapper jsonMapper) {
    var converter = new JacksonJsonMessageConverter(jsonMapper);
    var typeMapper = new DefaultJacksonJavaTypeMapper();
    // Only our own event package may be deserialized from the __TypeId__ header.
    typeMapper.setTrustedPackages(EventEnvelope.class.getPackageName());
    converter.setJavaTypeMapper(typeMapper);
    return converter;
  }

  @Bean
  Declarables studyMessaging() {
    var exchange = new TopicExchange("ciclo.events", true, false);
    var results =
        QueueBuilder.durable("study.ai-results")
            .withArgument("x-dead-letter-exchange", "ciclo.dlx")
            .build();
    return new Declarables(
        exchange,
        results,
        BindingBuilder.bind(results).to(exchange).with("ai.execution.completed"),
        BindingBuilder.bind(results).to(exchange).with("ai.execution.failed"));
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
            a ->
                a.requestMatchers("/actuator/health/**", "/internal/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(JwtRoleConverter.create())))
        .build();
  }
}
