package br.com.ciclo.ai.infrastructure.integration;

import br.com.ciclo.ai.application.AiPorts;
import br.com.ciclo.ai.application.AiPorts.Events;
import br.com.ciclo.shared.events.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

@Component
public class AiIntegrationAdapters implements Events, AiPorts.Objects {
  private final RabbitTemplate rabbit;
  private final S3Client s3;
  private final String bucket;

  public AiIntegrationAdapters(
      RabbitTemplate rabbit, S3Client s3, @Value("${app.s3.bucket:ciclo}") String bucket) {
    this.rabbit = rabbit;
    this.s3 = s3;
    this.bucket = bucket;
  }

  public byte[] get(String key) {
    return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
        .asByteArray();
  }

  public void completed(UUID jobId, Map<String, Object> result) {
    publish(
        "ai.execution.completed",
        jobId,
        Map.of("jobId", jobId.toString(), "status", "COMPLETED", "result", result));
  }

  public void failed(UUID jobId, String code, String message) {
    publish(
        "ai.execution.failed",
        jobId,
        Map.of(
            "jobId",
            jobId.toString(),
            "status",
            "FAILED",
            "errorCode",
            code,
            "errorMessage",
            message));
  }

  private void publish(String type, UUID id, Map<String, Object> payload) {
    rabbit.convertAndSend("ciclo.events", type, EventEnvelope.create(type, id, null, payload));
  }
}
