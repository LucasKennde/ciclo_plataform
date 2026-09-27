package br.com.ciclo.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ciclo.shared.events.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Reproduces the production bug: GatewayFilters.settings() consumes the
// platform.settings.changed EventEnvelope, and Boot's default SimpleMessageConverter rejects it
// because EventEnvelope (a plain record) is neither String, byte[] nor Serializable.
//
// Unlike study/ai/admin/identity, gateway-service has no datasource, so this can afford a full
// @SpringBootTest: it also proves Boot actually wires our single MessageConverter bean into the
// autoconfigured RabbitTemplate, not just that the bean exists somewhere in the context.
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class GatewayRabbitMessageConverterTest {
  @Autowired RabbitTemplate rabbitTemplate;

  @Test
  void roundTripsTheEnvelopeConsumedForMaintenanceMode() {
    EventEnvelope original =
        EventEnvelope.create(
            "platform.settings.changed", UUID.randomUUID(), null, Map.of("maintenanceMode", true));

    MessageConverter converter = rabbitTemplate.getMessageConverter();
    Message message = converter.toMessage(original, new MessageProperties());
    Object roundTripped = converter.fromMessage(message);

    assertThat(roundTripped).isEqualTo(original);
  }
}
