package br.com.ciclo.identity.infrastructure.config;

import br.com.ciclo.identity.application.IdentityApplicationService;
import br.com.ciclo.identity.application.IdentityPorts.*;
import br.com.ciclo.identity.infrastructure.security.SecurityAdapters;
import br.com.ciclo.shared.events.EventEnvelope;
import br.com.ciclo.shared.security.JwtRoleConverter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableMethodSecurity
public class IdentityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
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
  SecretKey jwtKey(@Value("${app.jwt-secret}") String secret) {
    return SecurityAdapters.key(secret);
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey key) {
    return NimbusJwtDecoder.withSecretKey(key).build();
  }

  @Bean
  IdentityApplicationService identityApplicationService(
      Users users,
      Workspaces workspaces,
      Passwords passwords,
      Tokens tokens,
      Sessions sessions,
      AccountTokens accountTokens,
      Mail mail,
      Settings settings,
      Events events) {
    return new IdentityApplicationService(
        users, workspaces, passwords, tokens, sessions, accountTokens, mail, settings, events);
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**", "/api/v1/auth/**")
                    .permitAll()
                    .requestMatchers("/internal/**")
                    .hasAuthority("SCOPE_service")
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(JwtRoleConverter.create())))
        .build();
  }
}
