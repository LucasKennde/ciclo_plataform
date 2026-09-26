package br.com.ciclo.admin.infrastructure;

import br.com.ciclo.admin.application.AdminPorts.Repository;
import br.com.ciclo.shared.events.EventEnvelope;
import java.time.Instant;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AdminAuditListener {
  private final Repository repository;

  public AdminAuditListener(Repository repository) {
    this.repository = repository;
  }

  @RabbitListener(queues = "admin.identity-audit")
  public void receive(EventEnvelope event) {
    Map<String, Object> payload = event.payload();
    repository.audit(
        required(payload, "action"),
        required(payload, "subject"),
        required(payload, "detail"),
        required(payload, "actor"),
        event.occurredAt() == null ? Instant.now() : event.occurredAt());
  }

  private String required(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new IllegalArgumentException("Evento de auditoria sem " + key);
    }
    return value.toString();
  }
}
