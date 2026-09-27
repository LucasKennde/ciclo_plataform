package br.com.ciclo.gateway;

import br.com.ciclo.shared.events.EventEnvelope;
import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class GatewayConfiguration {
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
