package br.com.ciclo.study.infrastructure.integration;

import br.com.ciclo.shared.events.EventEnvelope;
import br.com.ciclo.study.application.StudyPorts.Events;
import br.com.ciclo.study.application.StudyPorts.Objects;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class StudyAdapters implements Events, Objects {
  private final RabbitTemplate rabbit;
  private final S3Client s3;
  private final String bucket;

  public StudyAdapters(
      RabbitTemplate rabbit, S3Client s3, @Value("${app.s3.bucket:ciclo}") String bucket) {
    this.rabbit = rabbit;
    this.s3 = s3;
    this.bucket = bucket;
  }

  public void publish(String type, UUID correlationId, Map<String, Object> payload) {
    rabbit.convertAndSend(
        "ciclo.events", type, EventEnvelope.create(type, correlationId, null, payload));
  }

  public void put(String key, byte[] bytes, String contentType) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(bytes));
  }

  public byte[] get(String key) {
    return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
        .asByteArray();
  }
}
