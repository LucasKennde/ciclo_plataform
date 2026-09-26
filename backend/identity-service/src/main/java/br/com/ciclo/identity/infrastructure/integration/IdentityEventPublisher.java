package br.com.ciclo.identity.infrastructure.integration;

import br.com.ciclo.identity.application.IdentityPorts.Events;
import br.com.ciclo.shared.events.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdentityEventPublisher implements Events {
  private static final Logger LOGGER = LoggerFactory.getLogger(IdentityEventPublisher.class);

  private final RabbitTemplate rabbit;

  public IdentityEventPublisher(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  public void audit(String action, String subject, String detail, String actor) {
    String eventType = "identity.admin.audit";
    try {
      rabbit.convertAndSend(
          "ciclo.events",
          eventType,
          EventEnvelope.create(
              eventType,
              UUID.randomUUID(),
              null,
              Map.of(
                  "action", action,
                  "subject", subject,
                  "detail", detail,
                  "actor", actor)));
    } catch (AmqpException exception) {
      LOGGER.warn("Não foi possível publicar auditoria administrativa {}", action, exception);
    }
  }
}
