package br.com.ciclo.ai.infrastructure.integration;

import br.com.ciclo.ai.application.AiApplicationService;
import br.com.ciclo.ai.domain.AiCatalog.Operation;
import br.com.ciclo.shared.events.EventEnvelope;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AiJobListener {
  private final AiApplicationService ai;

  public AiJobListener(AiApplicationService ai) {
    this.ai = ai;
  }

  @RabbitListener(queues = "ai.requests")
  public void execute(EventEnvelope event) {
    var p = event.payload();
    UUID job = UUID.fromString(String.valueOf(p.get("jobId")));
    String principal = String.valueOf(p.get("workspaceId"));
    Operation operation = Operation.valueOf(String.valueOf(p.get("operation")));
    ai.execute(job, principal, operation, new LinkedHashMap<>(p));
  }
}
