package br.com.ciclo.ai.domain;

import java.time.Instant;
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
    SYLLABUS_EXTRACTION("Extração de edital"),
    MOCK_EXAM_EXTRACTION("Extração de simulado"),
    QUESTION_CLASSIFICATION("Classificação de questões"),
    QUESTION_GENERATION("Geração de questões");

    private final String displayName;

    Operation(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }
  }

  /**
   * Modelo cadastrado pelo administrador. A lista de modelos é dado, não código: fica em ai_models
   * e o admin escolhe exatamente o que a sua conta do provedor tem liberado.
   */
  public record Model(
      Provider provider,
      String id,
      String label,
      double inputUsdPerMillion,
      double outputUsdPerMillion,
      Set<Operation> operations,
      Instant updatedAt,
      String updatedBy) {

    public static final int MAX_ID_LENGTH = 120;
    public static final int MAX_LABEL_LENGTH = 120;

    public Model {
      id = normalizeId(id);
      label = normalizeLabel(label);
      if (inputUsdPerMillion < 0 || Double.isNaN(inputUsdPerMillion))
        throw new IllegalArgumentException("Preço de entrada inválido.");
      if (outputUsdPerMillion < 0 || Double.isNaN(outputUsdPerMillion))
        throw new IllegalArgumentException("Preço de saída inválido.");
      if (operations == null || operations.isEmpty())
        throw new IllegalArgumentException("Marque ao menos uma operação para o modelo.");
      operations = Set.copyOf(operations);
    }

    public boolean supports(Operation operation) {
      return operations.contains(operation);
    }

    private static String normalizeId(String raw) {
      if (raw == null || raw.isBlank())
        throw new IllegalArgumentException("Informe o id do modelo.");
      String value = raw.trim();
      if (value.length() > MAX_ID_LENGTH)
        throw new IllegalArgumentException(
            "O id do modelo deve ter no máximo " + MAX_ID_LENGTH + " caracteres.");
      // O id vai para a URL do provedor (Gemini) e para o corpo da requisição dos demais.
      if (value.chars().anyMatch(Character::isWhitespace))
        throw new IllegalArgumentException("O id do modelo não pode conter espaços.");
      return value;
    }

    private static String normalizeLabel(String raw) {
      if (raw == null || raw.isBlank())
        throw new IllegalArgumentException("Informe o nome do modelo.");
      String value = raw.trim();
      if (value.length() > MAX_LABEL_LENGTH)
        throw new IllegalArgumentException(
            "O nome do modelo deve ter no máximo " + MAX_LABEL_LENGTH + " caracteres.");
      return value;
    }
  }
}
