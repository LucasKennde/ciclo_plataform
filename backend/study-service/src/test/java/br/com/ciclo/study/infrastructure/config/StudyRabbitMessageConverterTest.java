package br.com.ciclo.study.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ciclo.shared.events.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.json.JsonMapper;

// Reproduces the production bug: study.uploadDocument() publishes an EventEnvelope through
// RabbitTemplate, and Boot's default SimpleMessageConverter rejects it because EventEnvelope
// (a plain record) is neither String, byte[] nor Serializable.
//
// This exercises StudyConfiguration#rabbitMessageConverter() directly instead of via
// @SpringBootTest: the full context needs a live Postgres for Flyway, which isn't available in
// every environment this runs in. GatewayCorsTest-style full-context wiring is covered instead
// by gateway-service, which has no datasource.
class StudyRabbitMessageConverterTest {
  @Test
  void roundTripsTheEnvelopePublishedOnDocumentUpload() {
    MessageConverter converter =
        new StudyConfiguration().rabbitMessageConverter(JsonMapper.builder().build());
    EventEnvelope original =
        EventEnvelope.create(
            "ai.execution.requested",
            UUID.randomUUID(),
            null,
            Map.of(
                "jobId",
                UUID.randomUUID().toString(),
                "workspaceId",
                UUID.randomUUID().toString(),
                "operation",
                "SYLLABUS_EXTRACTION",
                "aggregateId",
                UUID.randomUUID().toString(),
                "objectKey",
                "workspace/competitions/x/documents/y.pdf",
                "fileName",
                "edital.pdf"));

    Message message = converter.toMessage(original, new MessageProperties());
    Object roundTripped = converter.fromMessage(message);

    assertThat(roundTripped).isEqualTo(original);
  }
}
