package br.com.ciclo.ai.infrastructure.providers;

import br.com.ciclo.ai.application.AiPorts.*;
import br.com.ciclo.ai.domain.AiCatalog.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

@Component
public class ProviderHttpAdapter implements ProviderGateway {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final JsonMapper json;

  public ProviderHttpAdapter(JsonMapper json) {
    this.json = json;
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
      return switch (p) {
        case OPENAI -> openai(key, model, operation, inputNode);
        case ANTHROPIC -> anthropic(key, model, operation, inputNode);
        case GEMINI -> gemini(key, model, operation, inputNode);
      };
    } catch (Exception e) {
      if (e instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(e);
    }
  }

  private Result openai(String key, String model, Operation op, JsonNode input) throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("model", model);
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
    content.addObject().put("type", "input_text").put("text", prompt(op, input));
    var response =
        post("https://api.openai.com/v1/responses", body, Map.of("Authorization", "Bearer " + key));
    JsonNode node = send(response);
    String text = node.path("output_text").asText();
    if (text.isBlank()) text = findText(node);
    return result(
        text,
        node.path("usage").path("input_tokens").asLong(),
        node.path("usage").path("output_tokens").asLong());
  }

  private Result anthropic(String key, String model, Operation op, JsonNode input)
      throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("model", model);
    body.put("max_tokens", 8192);
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
    content.addObject().put("type", "text").put("text", prompt(op, input));
    var response =
        post(
            "https://api.anthropic.com/v1/messages",
            body,
            Map.of("x-api-key", key, "anthropic-version", "2023-06-01"));
    JsonNode node = send(response);
    return result(
        node.path("content").path(0).path("text").asText(),
        node.path("usage").path("input_tokens").asLong(),
        node.path("usage").path("output_tokens").asLong());
  }

  private Result gemini(String key, String model, Operation op, JsonNode input) throws Exception {
    ObjectNode body = json.createObjectNode();
    ObjectNode content = body.putArray("contents").addObject();
    ArrayNode parts = content.putArray("parts");
    if (input.hasNonNull("fileBase64")) {
      ObjectNode inline = parts.addObject().putObject("inline_data");
      inline.put("mime_type", "application/pdf");
      inline.put("data", input.path("fileBase64").asText());
    }
    parts.addObject().put("text", prompt(op, input));
    body.putObject("generationConfig").put("responseMimeType", "application/json");
    var response =
        post(
            "https://generativelanguage.googleapis.com/v1beta/models/"
                + model
                + ":generateContent?key="
                + key,
            body,
            Map.of());
    JsonNode node = send(response);
    return result(
        node.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(),
        node.path("usageMetadata").path("promptTokenCount").asLong(),
        node.path("usageMetadata").path("candidatesTokenCount").asLong());
  }

  private String prompt(Operation op, JsonNode input) {
    String context = input.toString();
    return switch (op) {
      case SYLLABUS_EXTRACTION ->
          "Extraia somente o conteúdo programático aplicável ao cargo "
              + input.path("role").asText()
              + " e banca "
              + input.path("board").asText()
              + ". Responda APENAS JSON no formato"
              + " {\"subjects\":[{\"id\":\"slug\",\"name\":\"nome\",\"weight\":1,\"topics\":[{\"id\":\"slug\",\"name\":\"nome\",\"weight\":1,\"evidence\":{\"page\":1,\"excerpt\":\"trecho\",\"confidence\":0.9},\"children\":[]}]}]}.";
      case MOCK_EXAM_EXTRACTION ->
          "Extraia somente questões com gabarito determinável do PDF. Responda APENAS JSON"
              + " {\"questions\":[{\"topicId\":\"unclassified\",\"statement\":\"...\",\"alternatives\":[\"...\"],\"correctIndex\":0,\"explanation\":\"...\"}]}."
              + " Não invente respostas.";
      case QUESTION_CLASSIFICATION ->
          "Classifique as questões nos tópicos fornecidos. Responda APENAS JSON"
              + " {\"assignments\":[{\"questionId\":\"...\",\"topicId\":\"...\"}]}. Entrada: "
              + context;
      case QUESTION_GENERATION ->
          "Crie "
              + input.path("count").asInt(10)
              + " questões de múltipla escolha, dificuldade "
              + input.path("difficulty").asInt(3)
              + ". Responda APENAS JSON {\"questions\":[{\"topicId\":\""
              + input.path("subjectId").asText("general")
              + "\",\"difficulty\":3,\"statement\":\"...\",\"alternatives\":[\"A\",\"B\",\"C\",\"D\"],\"correctIndex\":0,\"explanation\":\"...\"}]}.";
    };
  }

  private Result result(String text, long input, long output) {
    try {
      String clean = text.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
      Map<String, Object> parsed =
          json.readValue(clean, new TypeReference<Map<String, Object>>() {});
      return new Result(parsed, input, output);
    } catch (Exception e) {
      throw new IllegalStateException("O provedor retornou JSON inválido.");
    }
  }

  private JsonNode send(HttpRequest request) throws Exception {
    var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
            .timeout(Duration.ofSeconds(120))
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
}
