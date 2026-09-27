package br.com.ciclo.identity.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ciclo.shared.events.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

// Reproduces the production bug: IdentityEventPublisher.audit() publishes an EventEnvelope
// through RabbitTemplate, and Boot's default SimpleMessageConverter rejects it because
// EventEnvelope (a plain record) is neither String, byte[] nor Serializable.
//
// Exercises IdentityConfiguration#rabbitMessageConverter() directly instead of via
// @SpringBootTest: the full context needs a live Postgres for Flyway, which isn't available in
// every environment this runs in. Full-context wiring is covered instead by gateway-service,
// which has no datasource.
class IdentityRabbitMessageConverterTest {
  @Test
  void roundTripsTheEnvelopePublishedOnAdminAudit() {
    MessageConverter converter =
        new IdentityConfiguration().rabbitMessageConverter(JsonMapper.builder().build());
    EventEnvelope original =
        EventEnvelope.create(
            "identity.admin.audit",
            UUID.randomUUID(),
            null,
            Map.of(
                "action", "USER_SUSPENDED",
                "subject", UUID.randomUUID().toString(),
                "detail", "Suspenso pelo administrador",
                "actor", "admin@ciclo.local"));

    Message message = converter.toMessage(original, new MessageProperties());
    Object roundTripped = converter.fromMessage(message);

    assertThat(roundTripped).isEqualTo(original);
  }
}
