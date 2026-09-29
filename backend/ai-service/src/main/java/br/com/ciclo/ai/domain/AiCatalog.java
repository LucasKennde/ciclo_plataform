package br.com.ciclo.ai.domain;

import java.util.*;

public final class AiCatalog {
  private AiCatalog() {}

  public enum Provider {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    GEMINI("Gemini");

    private final String displayName;

    Provider(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }
  }

  public enum Operation {
    SYLLABUS_EXTRACTION,
    MOCK_EXAM_EXTRACTION,
    QUESTION_CLASSIFICATION,
    QUESTION_GENERATION
  }

  public record Model(
      String id,
      String label,
      double inputUsdPerMillion,
      double outputUsdPerMillion,
      Set<Operation> operations) {}

  public static final Map<Provider, List<Model>> MODELS =
      Map.of(
          Provider.OPENAI,
              List.of(
                  new Model(
                      "gpt-5.4-mini", "GPT-5.4 mini", 0.25, 2.0, EnumSet.allOf(Operation.class))),
          Provider.ANTHROPIC,
              List.of(
                  new Model(
                      "claude-sonnet-4-6",
                      "Claude Sonnet 4.6",
                      3,
                      15,
                      EnumSet.allOf(Operation.class))),
          Provider.GEMINI,
              List.of(
                  new Model(
                      "gemini-3.1-flash-lite",
                      "Gemini 3.1 Flash-Lite",
                      0.25,
                      1.5,
                      EnumSet.allOf(Operation.class))));

  public static Model requireModel(Provider provider, String id, Operation operation) {
    return MODELS.get(provider).stream()
        .filter(m -> m.id().equals(id) && m.operations().contains(operation))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Modelo incompatível com a operação."));
  }
}
