package br.com.ciclo.ai.infrastructure.providers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.ciclo.ai.domain.AiCatalog.Operation;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cada caso aqui é uma resposta que o {@code readValue} antigo rejeitava e que derrubava o job com
 * "O provedor retornou JSON inválido".
 */
class StructuredOutputTest {
  private final JsonMapper json = new JsonMapper();
  private final StructuredOutput output = new StructuredOutput(json);

  private JsonNode parse(Operation op, String raw) {
    JsonNode node = output.parse(op, raw);
    assertThat(node).as("esperava JSON interpretável").isNotNull();
    return node;
  }

  @Test
  void readsAPlainObject() {
    JsonNode node =
        parse(
            Operation.SYLLABUS_EXTRACTION,
            "{\"subjects\":[{\"id\":\"portugues\",\"name\":\"Português\",\"weight\":1,\"topics\":[]}]}");

    assertThat(node.get("subjects")).hasSize(1);
    assertThat(node.at("/subjects/0/name").asText()).isEqualTo("Português");
  }

  @Test
  void readsJsonWrappedInAMarkdownFence() {
    JsonNode node =
        parse(
            Operation.QUESTION_GENERATION,
            "Claro! Aqui está o resultado:\n"
                + "```json\n"
                + "{\"questions\":[{\"statement\":\"Q1\",\"alternatives\":[\"a\",\"b\",\"c\",\"d\"],\"correctIndex\":0}]}\n"
                + "```\n"
                + "Espero ter ajudado.");

    assertThat(node.get("questions")).hasSize(1);
    assertThat(node.at("/questions/0/statement").asText()).isEqualTo("Q1");
  }

  @Test
  void ignoresAnExampleObjectWrittenBeforeTheRealAnswer() {
    JsonNode node =
        parse(
            Operation.QUESTION_GENERATION,
            "O formato seria {\"questions\": []} e o resultado é {\"questions\":"
                + "[{\"statement\":\"Certainemente\",\"alternatives\":[\"a\",\"b\"],\"correctIndex\":1}]}");

    assertThat(node.at("/questions/0/statement").asText()).isEqualTo("Certainemente");
  }

  @Test
  void repairsTrailingCommas() {
    JsonNode node =
        parse(
            Operation.QUESTION_GENERATION,
            "{\"questions\":[{\"statement\":\"Q1\",\"alternatives\":[\"a\",\"b\",],\"correctIndex\":0,},],}");

    assertThat(node.get("questions")).hasSize(1);
  }

  @Test
  void acceptsABareArrayAndWrapsItInTheExpectedKey() {
    JsonNode node =
        parse(
            Operation.SYLLABUS_EXTRACTION,
            "[{\"name\":\"Matemática\",\"topics\":[{\"name\":\"Álgebra\"}]}]");

    assertThat(node.get("subjects")).hasSize(1);
    assertThat(node.at("/subjects/0/topics/0/name").asText()).isEqualTo("Álgebra");
  }

  @Test
  void acceptsPortugueseKeysBecauseThePromptIsInPortuguese() {
    JsonNode node =
        parse(
            Operation.SYLLABUS_EXTRACTION,
            "{\"disciplinas\":[{\"nome\":\"História\",\"topicos\":[{\"nome\":\"República\"}]}]}");

    assertThat(node.at("/subjects/0/name").asText()).isEqualTo("História");
    assertThat(node.at("/subjects/0/topics/0/name").asText()).isEqualTo("República");
  }

  @Test
  void buildsAnIdWhenTheModelOmitsIt() {
    JsonNode node =
        parse(
            Operation.SYLLABUS_EXTRACTION,
            "{\"subjects\":[{\"name\":\"Língua Portuguesa\",\"topics\":[]}]}");

    assertThat(node.at("/subjects/0/id").asText()).isEqualTo("lingua-portuguesa");
  }

  @Test
  void readsTheAnswerKeyAsALetter() {
    JsonNode node =
        parse(
            Operation.MOCK_EXAM_EXTRACTION,
            "{\"questions\":[{\"statement\":\"Q1\",\"alternatives\":[\"a\",\"b\",\"c\",\"d\"],"
                + "\"gabarito\":\"C\"}]}");

    assertThat(node.at("/questions/0/correctIndex").asInt()).isEqualTo(2);
  }

  @Test
  void readsTheAnswerKeyAsTheAlternativeText() {
    JsonNode node =
        parse(
            Operation.QUESTION_GENERATION,
            "{\"questions\":[{\"statement\":\"Q1\",\"alternatives\":[\"alpha\",\"beta\",\"gamma\"],"
                + "\"correctAnswer\":\"gamma\"}]}");

    assertThat(node.at("/questions/0/correctIndex").asInt()).isEqualTo(2);
  }

  @Test
  void clampsAnOutOfRangeAnswerKey() {
    JsonNode node =
        parse(
            Operation.QUESTION_GENERATION,
            "{\"questions\":[{\"statement\":\"Q1\",\"alternatives\":[\"a\",\"b\"],\"correctIndex\":7}]}");

    assertThat(node.at("/questions/0/correctIndex").asInt()).isEqualTo(1);
  }

  @Test
  void dropsItemsThatAreNotUsableAndKeepsTheRest() {
    JsonNode node =
        parse(
            Operation.QUESTION_GENERATION,
            "{\"questions\":["
                + "{\"statement\":\"Boa\",\"alternatives\":[\"a\",\"b\"],\"correctIndex\":0},"
                + "{\"statement\":\"Sem alternativas\",\"alternatives\":[]},"
                + "\"lixo\","
                + "{\"alternatives\":[\"a\",\"b\"]}"
                + "]}");

    assertThat(node.get("questions")).hasSize(1);
    assertThat(node.at("/questions/0/statement").asText()).isEqualTo("Boa");
  }

  @Test
  void acceptsAnEmptyResultInsteadOfFailing() {
    JsonNode node = parse(Operation.SYLLABUS_EXTRACTION, "{\"subjects\":[]}");

    assertThat(node.get("subjects")).isEmpty();
  }

  @Test
  void keepsNestedTopicTrees() {
    JsonNode node =
        parse(
            Operation.SYLLABUS_EXTRACTION,
            "{\"subjects\":[{\"name\":\"Direito\",\"topics\":[{\"name\":\"Constitucional\",\"children\":[{\"name\":\"Art."
                + " 5\",\"evidence\":{\"page\":12,\"excerpt\":\"...\",\"confidence\":0.9}}]}]}]}");

    assertThat(node.at("/subjects/0/topics/0/children/0/name").asText()).isEqualTo("Art. 5");
    assertThat(node.at("/subjects/0/topics/0/children/0/evidence/page").asInt()).isEqualTo(12);
  }

  @Test
  void returnsNullForATruncatedResponseSoTheCallerCanTellItApart() {
    assertThat(output.parse(Operation.SYLLABUS_EXTRACTION, "{\"subjects\":[{\"name\":\"A\""))
        .isNull();
  }

  @Test
  void returnsNullWhenThereIsNoJsonAtAll() {
    assertThat(output.parse(Operation.QUESTION_GENERATION, "Desculpe, não consegui processar."))
        .isNull();
  }

  @Test
  void excerptShowsWhatCameBack() {
    assertThat(StructuredOutput.excerpt("  \n olá  ")).isEqualTo("\"olá\"");
    // 220 caracteres + "…" + aspas de abertura e fechamento.
    assertThat(StructuredOutput.excerpt("x".repeat(400))).hasSize(223);
    assertThat(StructuredOutput.excerpt(null)).isEqualTo("(vazio)");
  }

  @Test
  void classifiesQuestionsIntoTopics() {
    JsonNode node =
        parse(
            Operation.QUESTION_CLASSIFICATION,
            "{\"assignments\":[{\"questionId\":\"q1\",\"topicId\":\"algebra\"},{\"questionId\":\"q2\"}]}");

    assertThat(node.get("assignments")).hasSize(1);
    assertThat(node.at("/assignments/0/topicId").asText()).isEqualTo("algebra");
  }

  @Test
  void doesNotRecurseForeverOnDeeplyNestedTopics() {
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 40; i++) deep.append("{\"name\":\"N").append(i).append("\",\"children\":[");
    deep.append("{\"name\":\"FIM\",\"children\":[]}");
    for (int i = 0; i < 40; i++) deep.append("]}");

    JsonNode node =
        parse(
            Operation.SYLLABUS_EXTRACTION,
            "{\"subjects\":[{\"name\":\"A\",\"topics\":" + deep + "}]}");

    assertThat(node.at("/subjects/0/topics")).isNotEmpty();
  }

  @Test
  void normalizesWeightsAndDefaultsMissingEvidence() {
    JsonNode node =
        parse(
            Operation.SYLLABUS_EXTRACTION,
            "{\"subjects\":[{\"name\":\"A\",\"weight\":0,\"topics\":[{\"name\":\"B\"}]}]}");

    assertThat(node.at("/subjects/0/weight").asDouble()).isEqualTo(1.0);
    assertThat(node.at("/subjects/0/topics/0/children").isArray()).isTrue();
  }

  @Test
  void unwrapsAProviderStyleResultEnvelope() {
    JsonNode node =
        parse(
            Operation.QUESTION_GENERATION,
            "{\"result\":{\"questions\":[{\"statement\":\"Q1\",\"alternatives\":[\"a\",\"b\"]}]}}");

    assertThat(node.get("questions")).hasSize(1);
  }

  @Test
  void everyOperationYieldsItsOwnTopLevelKey() {
    assertThat(parse(Operation.SYLLABUS_EXTRACTION, "{\"subjects\":[]}").has("subjects")).isTrue();
    assertThat(parse(Operation.MOCK_EXAM_EXTRACTION, "{\"questions\":[]}").has("questions"))
        .isTrue();
    assertThat(parse(Operation.QUESTION_GENERATION, "{\"questions\":[]}").has("questions"))
        .isTrue();
    assertThat(parse(Operation.QUESTION_CLASSIFICATION, "{\"assignments\":[]}").has("assignments"))
        .isTrue();
  }

  @Test
  void slugIsStableAndAscii() {
    assertThat(StructuredOutput.slug("Direito Constitucional — Art. 5º"))
        .isEqualTo("direito-constitucional-art-5");
    assertThat(StructuredOutput.slug("   ")).isEqualTo("topico");
  }

  @Test
  void handlesBracesInsideStringValues() {
    JsonNode node =
        parse(
            Operation.QUESTION_GENERATION,
            "{\"questions\":[{\"statement\":\"O {x} vale?\",\"alternatives\":[\"a\",\"b\"],"
                + "\"correctIndex\":0}]}");

    assertThat(node.at("/questions/0/statement").asText()).isEqualTo("O {x} vale?");
  }

  @Test
  void keepsEveryAlternativeForAMultiChoiceQuestion() {
    JsonNode node =
        parse(
            Operation.MOCK_EXAM_EXTRACTION,
            "{\"questions\":[{\"statement\":\"Q1\",\"alternatives\":[\"a\",\"b\",\"c\",\"d\",\"e\"],"
                + "\"correctIndex\":4}]}");

    assertThat(node.at("/questions/0/alternatives")).hasSize(5);
    assertThat(node.at("/questions/0/correctIndex").asInt()).isEqualTo(4);
  }

  @Test
  void subjectListMayArriveAsASingleObject() {
    JsonNode node =
        parse(Operation.SYLLABUS_EXTRACTION, "{\"subjects\":{\"name\":\"Única\",\"topics\":[]}}");

    assertThat(node.get("subjects")).hasSize(1);
  }

  @Test
  void unknownOperationKeysStillYieldTheExpectedShape() {
    // O modelo inventou o nome da lista; a lista sai vazia, mas o contrato é respeitado.
    JsonNode node =
        parse(Operation.SYLLABUS_EXTRACTION, "{\"conteudo_qualquer\":[{\"name\":\"A\"}]}");

    assertThat(node.get("subjects")).isEmpty();
    assertThat(((ObjectNode) node).propertyNames()).containsExactly("subjects");
  }

  @Test
  void slugsWithoutIdSurviveEveryTopicDepth() {
    String json1 =
        "{\"subjects\":[{\"name\":\"A\",\"topics\":[{\"name\":\"B\",\"children\":[{\"name\":\"C\"}]}]}]}";

    JsonNode node = parse(Operation.SYLLABUS_EXTRACTION, json1);

    assertThat(node.at("/subjects/0/topics/0/children/0/id").asText()).isEqualTo("c");
  }

  @Test
  void confidenceIsReadAsCommaDecimal() {
    JsonNode node =
        parse(
            Operation.SYLLABUS_EXTRACTION,
            "{\"subjects\":[{\"name\":\"A\",\"topics\":[{\"name\":\"B\",\"evidence\":{\"page\":3,"
                + "\"confidence\":\"0,8\"}}]}]}");

    assertThat(node.at("/subjects/0/topics/0/evidence/confidence").asDouble()).isEqualTo(0.8);
  }

  @Test
  void operationsAreMappedToTheRightNormalizer() {
    assertThat(parse(Operation.MOCK_EXAM_EXTRACTION, "{\"questions\":[]}").has("questions"))
        .isTrue();
    assertThat(List.of(Operation.values())).hasSize(4);
  }
}
