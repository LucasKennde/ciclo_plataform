package br.com.ciclo.study.infrastructure.integration;

import br.com.ciclo.shared.events.EventEnvelope;
import br.com.ciclo.study.application.StudyApplicationService;
import br.com.ciclo.study.application.StudyPorts.AiResult;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AiResultListener {
  private final StudyApplicationService study;
  private final JsonMapper json;

  public AiResultListener(StudyApplicationService study, JsonMapper json) {
    this.study = study;
    this.json = json;
  }

  @RabbitListener(queues = "study.ai-results")
  public void receive(EventEnvelope event) {
    var p = event.payload();
    UUID jobId = UUID.fromString(String.valueOf(p.get("jobId")));
    String status = String.valueOf(p.get("status"));
    AiResult result =
        json.convertValue(p.getOrDefault("result", java.util.Map.of()), AiResult.class);
    study.applyAiResult(
        jobId, status, result, (String) p.get("errorCode"), (String) p.get("errorMessage"));
  }
}
