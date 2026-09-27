package br.com.ciclo.admin.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ciclo.shared.events.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

// Reproduces the production bug: settingsChanged() (the maintenance-mode kill switch) publishes
// an EventEnvelope through RabbitTemplate, and Boot's default SimpleMessageConverter rejects it
// because EventEnvelope (a plain record) is neither String, byte[] nor Serializable.
//
// Exercises AdminConfiguration#rabbitMessageConverter() directly instead of via @SpringBootTest:
// the full context needs a live Postgres for Flyway, which isn't available in every environment
// this runs in. Full-context wiring is covered instead by gateway-service, which has no
// datasource.
class AdminRabbitMessageConverterTest {
  @Test
  void roundTripsTheEnvelopePublishedOnSettingsChanged() {
    MessageConverter converter =
        new AdminConfiguration().rabbitMessageConverter(JsonMapper.builder().build());
    // AdminEventPublisher.settingsChanged() puts a raw boolean in the payload map, unlike the
    // other services' publishers, which only ever put strings.
    EventEnvelope original =
        EventEnvelope.create(
            "platform.settings.changed", UUID.randomUUID(), null, Map.of("maintenanceMode", true));

    Message message = converter.toMessage(original, new MessageProperties());
    Object roundTripped = converter.fromMessage(message);

    assertThat(roundTripped).isEqualTo(original);
  }
}
