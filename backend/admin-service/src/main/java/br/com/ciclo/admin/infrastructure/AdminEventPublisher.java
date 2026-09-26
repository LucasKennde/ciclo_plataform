package br.com.ciclo.admin.infrastructure;

import br.com.ciclo.admin.application.AdminPorts.Events;
import br.com.ciclo.shared.events.EventEnvelope;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminEventPublisher implements Events {
  private final RabbitTemplate rabbit;

  public AdminEventPublisher(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  public void settingsChanged(boolean maintenanceMode) {
    rabbit.convertAndSend(
        "ciclo.events",
        "platform.settings.changed",
        EventEnvelope.create(
            "platform.settings.changed",
            UUID.randomUUID(),
            null,
            Map.of("maintenanceMode", maintenanceMode)));
  }
}
