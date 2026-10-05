package br.com.ciclo.ai.infrastructure.providers;

import br.com.ciclo.ai.application.AiPorts.*;
import br.com.ciclo.ai.domain.AiCatalog.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

/**
 * Chama cada provedor e devolve sempre JSON no contrato do study.
 *
 * <p>Três defesas, porque o mesmo sintoma ("JSON inválido") vinha de três causas distintas: o
 * provedor recortou a resposta no limite de tokens; o modelo devolveu o JSON dentro de texto ou
 * cercado em markdown; e o modelo simplesmente errou o formato. Na ordem, detectamos o corte,
 * forçamos JSON nativo onde a API oferece, e reparamos o texto na volta.
 */
@Component
public class ProviderHttpAdapter implements ProviderGateway {
  /** Claude corta em 8k por padrão e um edital extraído passa disso com folga. */
  private static final int MAX_OUTPUT_TOKENS = 32000;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final JsonMapper json;
  private final StructuredOutput output;

  public ProviderHttpAdapter(JsonMapper json) {
    this.json = json;
    this.output = new StructuredOutput(json);
  }

  public TestResult test(Provider p, String key) {
    try {
      HttpRequest request =
          switch (p) {
            case OPENAI ->
                get("https://api.openai.com/v1/models", "Authorization", "Bearer " + key);
            case ANTHROPIC ->
                get(
                    "https://api.anthropic.com/v1/models",
                    "x-api-key",
                    key,
                    "anthropic-version",
                    "2023-06-01");
            case GEMINI ->
                get("https://generativelanguage.googleapis.com/v1beta/models?key=" + key);
          };
      var response = http.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() < 400
          ? new TestResult(true, null)
          : new TestResult(
              false, "O provedor rejeitou a credencial (HTTP " + response.statusCode() + ").");
    } catch (Exception e) {
      return new TestResult(false, "Não foi possível testar a credencial: " + e.getMessage());
    }
  }

  public Result execute(
      Provider p, String key, String model, Operation operation, Map<String, Object> input) {
    try {
      JsonNode inputNode = json.valueToTree(input);
      Reply first = call(p, key, model, operation, inputNode, false);
      JsonNode parsed = output.parse(operation, first.text());
      if (parsed != null)
        return new Result(toMap(parsed), first.inputTokens(), first.outputTokens());

      if (first.truncated()) {
        // Repetir não resolve: a resposta é longa demais, não está malformada.
        throw new IllegalStateException(
            "A resposta da IA foi cortada no limite de "
                + MAX_OUTPUT_TOKENS
                + " tokens ("
                + p.displayName()
                + "/"
                + model
                + "). Reduza o tamanho do edital ou divida o processamento.");
      }

      Reply retry = call(p, key, model, operation, inputNode, true);
      JsonNode repaired = output.parse(operation, retry.text());
      // Soma as duas chamadas: o reparo é uma chamada completa e custa o mesmo. Antes só o retry
      // era contabilizado e o custo real da extração ficava subnotado na tela de consumo.
      if (repaired != null)
        return new Result(
            toMap(repaired),
            first.inputTokens() + retry.inputTokens(),
            first.outputTokens() + retry.outputTokens());

      throw new IllegalStateException(
          "A IA não devolveu um resultado utilizável após uma tentativa de reparo. Resposta"
              + " recebida: "
              + StructuredOutput.excerpt(retry.text()));
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(e);
    }
  }

  // --- chamadas por provedor ------------------------------------------------------------------

  private Reply call(
      Provider p, String key, String model, Operation operation, JsonNode input, boolean repair)
      throws Exception {
    return switch (p) {
      case OPENAI -> openai(key, model, operation, input, repair);
      case ANTHROPIC -> anthropic(key, model, operation, input, repair);
      case GEMINI -> gemini(key, model, operation, input, repair);
    };
  }

  private Reply openai(String key, String model, Operation op, JsonNode input, boolean repair)
      throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("model", model);
    body.putObject("text").putObject("format").put("type", "json_object");
    ArrayNode entries = body.putArray("input");
    ObjectNode message = entries.addObject();
    message.put("role", "user");
    ArrayNode content = message.putArray("content");
    if (input.hasNonNull("fileBase64")) {
      ObjectNode file = content.addObject();
      file.put("type", "input_file");
      file.put("filename", input.path("fileName").asText("document.pdf"));
      file.put("file_data", "data:application/pdf;base64," + input.path("fileBase64").asText());
    }
    content.addObject().put("type", "input_text").put("text", prompt(op, input, repair));
    var response =
        post("https://api.openai.com/v1/responses", body, Map.of("Authorization", "Bearer " + key));
    JsonNode node = send(response);
    String text = node.path("output_text").asText();
    if (text.isBlank()) text = findText(node);
    boolean truncated =
        "incomplete".equals(node.path("status").asText())
            && "max_output_tokens".equals(node.path("incomplete_details").path("reason").asText());
    return new Reply(
        text,
        node.path("usage").path("input_tokens").asLong(),
        node.path("usage").path("output_tokens").asLong(),
        truncated);
  }

  private Reply anthropic(String key, String model, Operation op, JsonNode input, boolean repair)
      throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("model", model);
    body.put("max_tokens", MAX_OUTPUT_TOKENS);
    ObjectNode message = body.putArray("messages").addObject();
    message.put("role", "user");
    ArrayNode content = message.putArray("content");
    if (input.hasNonNull("fileBase64")) {
      ObjectNode document = content.addObject();
      document.put("type", "document");
      ObjectNode source = document.putObject("source");
      source.put("type", "base64");
      source.put("media_type", "application/pdf");
      source.put("data", input.path("fileBase64").asText());
    }
    content.addObject().put("type", "text").put("text", prompt(op, input, repair));
    var response =
        post(
            "https://api.anthropic.com/v1/messages",
            body,
            Map.of("x-api-key", key, "anthropic-version", "2023-06-01"));
    JsonNode node = send(response);
    return new Reply(
        node.path("content").path(0).path("text").asText(),
        node.path("usage").path("input_tokens").asLong(),
        node.path("usage").path("output_tokens").asLong(),
        "max_tokens".equals(node.path("stop_reason").asText()));
  }

  private Reply gemini(String key, String model, Operation op, JsonNode input, boolean repair)
      throws Exception {
    ObjectNode body = json.createObjectNode();
    ObjectNode content = body.putArray("contents").addObject();
    ArrayNode parts = content.putArray("parts");
    if (input.hasNonNull("fileBase64")) {
      ObjectNode inline = parts.addObject().putObject("inline_data");
      inline.put("mime_type", "application/pdf");
      inline.put("data", input.path("fileBase64").asText());
    }
    parts.addObject().put("text", prompt(op, input, repair));
    ObjectNode config = body.putObject("generationConfig");
    config.put("responseMimeType", "application/json");
    config.put("temperature", 0);
    var response =
        post(
            "https://generativelanguage.googleapis.com/v1beta/models/"
                + model
                + ":generateContent?key="
                + key,
            body,
            Map.of());
    JsonNode node = send(response);
    return new Reply(
        node.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(),
        node.path("usageMetadata").path("promptTokenCount").asLong(),
        node.path("usageMetadata").path("candidatesTokenCount").asLong(),
        "MAX_TOKENS".equals(node.path("candidates").path(0).path("finishReason").asText()));
  }

  // --- prompts ---------------------------------------------------------------------------------

  private String prompt(Operation op, JsonNode input, boolean repair) {
    String instruction =
        (repair ? REPAIR_PREFIX : "")
            + switch (op) {
              case SYLLABUS_EXTRACTION ->
                  "Extraia do edital o conteúdo programático aplicável ao cargo "
                      + input.path("role").asText()
                      + " e à banca "
                      + input.path("board").asText()
                      + ". Responda SOMENTE com um objeto JSON válido, sem cercas de markdown e sem"
                      + " qualquer texto antes ou depois, no formato exato:"
                      + " {\"subjects\":[{\"id\":\"slug-em-kebab-case\",\"name\":\"nome da"
                      + " disciplina\",\"weight\":1,\"topics\":[{\"id\":\"slug-em-kebab-case\",\"name\":\"nome"
                      + " do tópico\",\"weight\":1,\"evidence\":{\"page\":1,\"excerpt\":\"trecho"
                      + " literal\",\"confidence\":0.9},\"children\":[]}]}]}. Liste a árvore"
                      + " completa de subtópicos em \"children\". Se o edital não trouxer conteúdo"
                      + " programático, responda {\"subjects\":[]}.";
              case MOCK_EXAM_EXTRACTION ->
                  "Extraia do PDF somente as questões cujo gabarito é determinável. Responda"
                      + " SOMENTE com um objeto JSON válido, sem cercas de markdown e sem texto ao"
                      + " redor, no formato exato:"
                      + " {\"questions\":[{\"topicId\":\"unclassified\",\"statement\":\"enunciado"
                      + " completo\",\"alternatives\":[\"A\",\"B\",\"C\",\"D\"],\"correctIndex\":0,\"explanation\":\"justificativa"
                      + " breve\"}]}. \"correctIndex\" começa em 0. Não invente questões nem"
                      + " respostas.";
              case QUESTION_CLASSIFICATION ->
                  "Classifique cada questão informada nos tópicos fornecidos. Responda SOMENTE com"
                      + " um objeto JSON válido, sem cercas de markdown e sem texto ao redor, no"
                      + " formato exato: {\"assignments\":[{\"questionId\":\"id"
                      + " recebido\",\"topicId\":\"id do tópico\"}]}. Entrada: "
                      + input;
              case QUESTION_GENERATION ->
                  "Crie "
                      + input.path("count").asInt(10)
                      + " questões de múltipla escolha, dificuldade "
                      + input.path("difficulty").asInt(3)
                      + " (de 1 a 5). Responda SOMENTE com um objeto JSON válido, sem cercas de"
                      + " markdown e sem texto ao redor, no formato exato:"
                      + " {\"questions\":[{\"topicId\":\""
                      + input.path("subjectId").asText("unclassified")
                      + "\",\"boardStyle\":\"estilo da"
                      + " banca\",\"difficulty\":3,\"statement\":\"enunciado"
                      + " completo\",\"alternatives\":[\"A\",\"B\",\"C\",\"D\"],\"correctIndex\":0,\"explanation\":\"justificativa"
                      + " breve\"}]}. \"correctIndex\" começa em 0.";
            };
    return instruction;
  }

  private static final String REPAIR_PREFIX =
      "Sua resposta anterior não pôde ser interpretada como JSON. "
          + "Responda novamente com UM ÚNICO objeto JSON válido, sem cercas de markdown (```), "
          + "sem comentários e sem nenhum texto antes ou depois do JSON. ";

  // --- transporte e leitura ---------------------------------------------------------------------

  private Map<String, Object> toMap(JsonNode node) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = json.convertValue(node, Map.class);
    return map == null ? Map.of() : map;
  }

  private JsonNode send(HttpRequest request) throws Exception {
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400)
      throw new IllegalStateException(
          "Falha no provedor (HTTP "
              + response.statusCode()
              + "): "
              + response.body().substring(0, Math.min(300, response.body().length())));
    return json.readTree(response.body());
  }

  private HttpRequest post(String url, JsonNode body, Map<String, String> headers)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(300))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    headers.forEach(b::header);
    return b.build();
  }

  private HttpRequest get(String url, String... headers) {
    var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET();
    for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
    return b.build();
  }

  private String findText(JsonNode node) {
    for (JsonNode output : node.path("output"))
      for (JsonNode content : output.path("content"))
        if (content.has("text")) return content.path("text").asText();
    return "";
  }

  /** Texto devolvido pelo provedor + contadores + se a API avisou que cortou a resposta. */
  private record Reply(String text, long inputTokens, long outputTokens, boolean truncated) {}
}
