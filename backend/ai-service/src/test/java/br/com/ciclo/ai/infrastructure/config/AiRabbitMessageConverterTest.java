package br.com.ciclo.ai.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ciclo.shared.events.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

// Reproduces the production bug: publishing ai.execution.completed/failed sends an EventEnvelope
// through RabbitTemplate, and Boot's default SimpleMessageConverter rejects it because
// EventEnvelope (a plain record) is neither String, byte[] nor Serializable.
//
// Exercises AiConfiguration#rabbitMessageConverter() directly instead of via @SpringBootTest:
// the full context needs a live Postgres for Flyway, which isn't available in every environment
// this runs in. Full-context wiring is covered instead by gateway-service, which has no
// datasource.
class AiRabbitMessageConverterTest {
  @Test
  void roundTripsTheEnvelopePublishedOnExecutionCompleted() {
    MessageConverter converter =
        new AiConfiguration().rabbitMessageConverter(JsonMapper.builder().build());
    UUID jobId = UUID.randomUUID();
    EventEnvelope original =
        EventEnvelope.create(
            "ai.execution.completed",
            jobId,
            null,
            Map.of(
                "jobId",
                jobId.toString(),
                "status",
                "COMPLETED",
                // AiIntegrationAdapters.completed() nests the extraction result as a map.
                "result",
                Map.of(
                    "subjects",
                    "Direito Constitucional",
                    "topics",
                    "Controle de constitucionalidade")));

    Message message = converter.toMessage(original, new MessageProperties());
    Object roundTripped = converter.fromMessage(message);

    assertThat(roundTripped).isEqualTo(original);
  }
}
