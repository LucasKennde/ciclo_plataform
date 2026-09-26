package br.com.ciclo.study.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import br.com.ciclo.shared.events.EventEnvelope;
import br.com.ciclo.study.application.StudyApplicationService;
import br.com.ciclo.study.application.StudyPorts.AiResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

class AiResultListenerTest {
  @Test
  void convertsTheExternalMapToTheTypedApplicationContract() {
    var study = Mockito.mock(StudyApplicationService.class);
    var listener = new AiResultListener(study, JsonMapper.builder().findAndAddModules().build());
    var jobId = UUID.randomUUID();
    var result =
        Map.<String, Object>of(
            "subjects",
            List.of(
                Map.of(
                    "id",
                    "law",
                    "name",
                    "Direito",
                    "weight",
                    1,
                    "topics",
                    List.of(
                        Map.of(
                            "id",
                            "constitutional",
                            "name",
                            "Constitucional",
                            "weight",
                            1,
                            "children",
                            List.of())))),
            "questions",
            List.of());
    var event =
        EventEnvelope.create(
            "ai.execution.completed",
            jobId,
            null,
            Map.of("jobId", jobId.toString(), "status", "COMPLETED", "result", result));
    var converted = ArgumentCaptor.forClass(AiResult.class);

    listener.receive(event);

    verify(study).applyAiResult(eq(jobId), eq("COMPLETED"), converted.capture(), any(), any());
    assertThat(converted.getValue().subjects()).hasSize(1);
    assertThat(converted.getValue().subjects().get(0).topics().get(0).id())
        .isEqualTo("constitutional");
  }
}
