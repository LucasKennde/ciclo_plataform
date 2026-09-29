package br.com.ciclo.ai.infrastructure.providers;

import br.com.ciclo.ai.domain.AiCatalog.Operation;
import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converte o texto cru de um modelo no JSON que o study espera consumir.
 *
 * <p>Antes, {@code ProviderHttpAdapter} fazia {@code readValue} direto em {@code Map<String,
 * Object>}: qualquer cercadura de markdown, qualquer frase antes do JSON, vírgula no fim ou
 * resposta truncada derrubava o job inteiro com "O provedor retornou JSON inválido". Aqui o texto é
 * lido em camadas — extração, reparo e normalização — para que a mesma saída sirva para OpenAI,
 * Anthropic e Gemini, e para que um item malformado seja descartado em vez de reprovar a extração
 * inteira.
 */
final class StructuredOutput {
  private static final int MAX_CANDIDATES = 8;
  private static final int MAX_TOPIC_DEPTH = 6;
  private static final int MIN_ALTERNATIVES = 2;
  private static final int MAX_DIFFICULTY = 5;

  private static final List<String> SUBJECT_KEYS =
      List.of("subjects", "disciplinas", "syllabus", "subjectsList", "conteudos");
  private static final List<String> QUESTION_KEYS =
      List.of("questions", "questoes", "questionsList", "itens", "questoesExtraidas");
  private static final List<String> ASSIGNMENT_KEYS =
      List.of("assignments", "classifications", "classificacoes", "assignmentsList", "results");

  private final JsonMapper json;

  StructuredOutput(JsonMapper json) {
    this.json = json;
  }

  /** Nó normalizado, ou null quando não achou JSON interpretável no texto. */
  JsonNode parse(Operation operation, String raw) {
    JsonNode best = null;
    int bestScore = -1;
    for (String candidate : candidates(raw)) {
      JsonNode normalized = tryParse(operation, candidate);
      if (normalized == null) continue;
      // Vários modelos escrevem o formato de exemplo antes do resultado real, e o exemplo é JSON
      // válido —parsers que aceitam o primeiro candidato devolvem lista vazia. Fica o mais rico.
      int score = score(normalized);
      if (score > bestScore) {
        best = normalized;
        bestScore = score;
      }
    }
    return best;
  }

  private JsonNode tryParse(Operation operation, String candidate) {
    try {
      JsonNode node = json.readTree(candidate);
      if (node == null) return null;
      JsonNode root = node.isArray() ? wrap(operation, node) : node;
      return root.isObject() ? normalize(operation, (ObjectNode) root) : null;
    } catch (Exception e) {
      return null;
    }
  }

  private int score(JsonNode normalized) {
    for (String key : List.of("subjects", "questions", "assignments")) {
      JsonNode list = normalized.get(key);
      if (list != null) return list.size();
    }
    return 0;
  }

  /** Resumo curto do que o modelo devolveu, para a mensagem de erro não ser opaca. */
  static String excerpt(String raw) {
    if (raw == null) return "(vazio)";
    String clean = raw.replaceAll("\\s+", " ").trim();
    if (clean.isEmpty()) return "(vazio)";
    return "\"" + (clean.length() > 220 ? clean.substring(0, 220) + "…" : clean) + "\"";
  }

  /**
   * Varre o texto atrás do primeiro JSON balanceado. Como o objeto termina no fechamento
   * correspondente, cercaduras ``` e frases introdutórias caem de graça — não é preciso tratamento
   * separado de markdown.
   */
  private static List<String> candidates(String raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) return out;
    String text = raw.replace("\uFEFF", "");
    int i = 0;
    while (i < text.length() && out.size() < MAX_CANDIDATES) {
      char c = text.charAt(i);
      if (c != '{' && c != '[') {
        i++;
        continue;
      }
      String candidate = balanced(text, i);
      if (candidate == null) {
        // Truncado ou não-JSON: pula o{abrigo de abertura para não reprocessar o mesmo trecho.
        i++;
        continue;
      }
      out.add(candidate);
      i += candidate.length();
    }
    return out;
  }

  private static String balanced(String text, int start) {
    char open = text.charAt(start);
    char close = open == '{' ? '}' : ']';
    StringBuilder sb = new StringBuilder();
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = start; i < text.length(); i++) {
      char c = text.charAt(i);
      if (inString) {
        sb.append(c);
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') inString = false;
        continue;
      }
      if (c == '"') {
        inString = true;
        sb.append(c);
        continue;
      }
      if (c == open) {
        depth++;
        sb.append(c);
        continue;
      }
      if (c == close && --depth == 0) {
        return sb.append(c).toString();
      }
      if (c == ',') {
        // Vírgula final antes de fechar: o parser estrito rejeita, o repair remove.
        int j = i + 1;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
        if (j < text.length() && (text.charAt(j) == '}' || text.charAt(j) == ']')) continue;
      }
      sb.append(c);
    }
    return null;
  }

  /** Resposta veio como lista pura: embrulha na chave que a operação espera. */
  private ObjectNode wrap(Operation operation, JsonNode array) {
    ObjectNode root = json.createObjectNode();
    root.set(singular(operation), array);
    return root;
  }

  private String singular(Operation operation) {
    return switch (operation) {
      case SYLLABUS_EXTRACTION -> "subjects";
      case MOCK_EXAM_EXTRACTION, QUESTION_GENERATION -> "questions";
      case QUESTION_CLASSIFICATION -> "assignments";
    };
  }

  private JsonNode normalize(Operation operation, ObjectNode root) {
    ObjectNode out = json.createObjectNode();
    switch (operation) {
      case SYLLABUS_EXTRACTION -> out.set("subjects", subjects(pick(root, SUBJECT_KEYS), 0));
      case MOCK_EXAM_EXTRACTION, QUESTION_GENERATION ->
          out.set("questions", questions(pick(root, QUESTION_KEYS)));
      case QUESTION_CLASSIFICATION ->
          out.set("assignments", assignments(pick(root, ASSIGNMENT_KEYS)));
    }
    return out;
  }

  /** Procura a lista em qualquer um dos apelidos, direto na raiz ou dentro de um wrapper. */
  private JsonNode pick(ObjectNode root, List<String> keys) {
    for (String key : keys) {
      JsonNode direct = root.get(key);
      if (direct != null) return unwrap(direct);
    }
    // Alguns modelos embrulham em {"resultado": {...}}.
    for (JsonNode value : root.values()) {
      if (value != null && value.isObject()) {
        for (String key : keys) {
          JsonNode nested = value.get(key);
          if (nested != null) return unwrap(nested);
        }
      }
    }
    return json.createArrayNode();
  }

  private JsonNode unwrap(JsonNode node) {
    if (node.isArray()) return node;
    // Objeto único onde se esperava lista: embrulha em lista de um.
    if (node.isObject()) {
      ArrayNode single = json.createArrayNode();
      single.add(node);
      return single;
    }
    return json.createArrayNode();
  }

  private ArrayNode subjects(JsonNode list, int depth) {
    ArrayNode out = json.createArrayNode();
    for (JsonNode node : list) {
      if (node == null || !node.isObject()) continue;
      ObjectNode source = (ObjectNode) node;
      String name = text(source, "name", "nome", "label", "titulo", "disciplina");
      if (name == null) continue;
      ObjectNode subject = json.createObjectNode();
      subject.put("id", idOrSlug(source, name, "id", "slug", "codigo", "code"));
      subject.put("name", name);
      subject.put("weight", weight(source));
      subject.set(
          "topics",
          topics(pick(source, List.of("topics", "topicos", "assuntos", "itens")), depth + 1));
      out.add(subject);
    }
    return out;
  }

  private ArrayNode topics(JsonNode list, int depth) {
    ArrayNode out = json.createArrayNode();
    if (depth > MAX_TOPIC_DEPTH) return out;
    for (JsonNode node : list) {
      if (node == null || !node.isObject()) continue;
      ObjectNode source = (ObjectNode) node;
      String name = text(source, "name", "nome", "label", "titulo", "assunto");
      if (name == null) continue;
      ObjectNode topic = json.createObjectNode();
      topic.put("id", idOrSlug(source, name, "id", "slug", "codigo", "code"));
      topic.put("name", name);
      topic.put("weight", weight(source));
      JsonNode evidence = evidence(source);
      if (evidence != null) topic.set("evidence", evidence);
      topic.set(
          "children", topics(pick(source, List.of("children", "filhos", "subtopicos")), depth + 1));
      out.add(topic);
    }
    return out;
  }

  private ObjectNode evidence(ObjectNode source) {
    JsonNode node = source.get("evidence");
    if (node == null) node = source.get("evidencia");
    if (node == null || !node.isObject()) return null;
    ObjectNode out = json.createObjectNode();
    out.put("page", (int) number(node, 1, "page", "pagina", "p"));
    String excerpt = text(node, "excerpt", "trecho", "texto");
    out.put("excerpt", excerpt == null ? "" : excerpt);
    out.put("confidence", number(node, 0.5, "confidence", "confianca", "confianca0a1"));
    return out;
  }

  private ArrayNode questions(JsonNode list) {
    ArrayNode out = json.createArrayNode();
    for (JsonNode node : list) {
      if (node == null || !node.isObject()) continue;
      ObjectNode source = (ObjectNode) node;
      String statement = text(source, "statement", "enunciado", "question", "texto");
      if (statement == null) continue;
      JsonNode alternatives =
          pick(source, List.of("alternatives", "alternativas", "options", "choices"));
      if (!alternatives.isArray() || alternatives.size() < MIN_ALTERNATIVES) continue;
      ObjectNode question = json.createObjectNode();
      question.put(
          "topicId",
          orDefault(text(source, "topicId", "topicoId", "topic", "disciplina"), "unclassified"));
      String boardStyle = text(source, "boardStyle", "estiloBanca", "banca", "board");
      question.put("boardStyle", boardStyle);
      question.put(
          "difficulty",
          (int) clamp(number(source, 3, "difficulty", "dificuldade"), 1, MAX_DIFFICULTY));
      question.put("statement", statement);
      ArrayNode alts = json.createArrayNode();
      for (JsonNode alt : alternatives) {
        if (alt != null && !alt.isNull() && !alt.asText().isBlank()) alts.add(alt.asText().trim());
      }
      if (alts.size() < MIN_ALTERNATIVES) continue;
      question.set("alternatives", alts);
      question.put("correctIndex", correctIndex(source, alts));
      String explanation = text(source, "explanation", "explicacao", "justificativa", "rationale");
      question.put("explanation", explanation);
      out.add(question);
    }
    return out;
  }

  /**
   * Aceita o gabarito como índice, como letra ("B", "b") ou como o próprio texto da alternativa,
   * que é como a maioria dos modelos responde mesmo quando o prompt pede índice.
   */
  private int correctIndex(ObjectNode source, ArrayNode alternatives) {
    int size = alternatives.size();
    JsonNode node =
        first(
            source,
            "correctIndex",
            "correct_index",
            "gabarito",
            "respostaCorreta",
            "correctAnswer",
            "answer");
    if (node == null || node.isNull()) return 0;
    if (node.isNumber()) return (int) clamp(node.asDouble(), 0, size - 1);
    String value = node.asText().trim();
    if (value.isEmpty()) return 0;
    if (value.length() == 1 && Character.isLetter(value.charAt(0))) {
      return Math.max(0, Math.min(Character.toUpperCase(value.charAt(0)) - 'A', size - 1));
    }
    if (value.length() == 1 && Character.isDigit(value.charAt(0))) {
      return (int) clamp(value.charAt(0) - '0', 0, size - 1);
    }
    for (int i = 0; i < size; i++) {
      if (alternatives.get(i).asText().trim().equalsIgnoreCase(value)) return i;
    }
    int parsed;
    try {
      parsed = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return 0;
    }
    if (parsed == size) return 0;
    return (int) clamp(parsed, 0, size - 1);
  }

  private ArrayNode assignments(JsonNode list) {
    ArrayNode out = json.createArrayNode();
    for (JsonNode node : list) {
      if (node == null || !node.isObject()) continue;
      ObjectNode source = (ObjectNode) node;
      String questionId = text(source, "questionId", "question_id", "id", "questaoId", "question");
      String topicId = text(source, "topicId", "topic_id", "topicoId", "topic", "idTopico");
      if (questionId == null || topicId == null) continue;
      ObjectNode assignment = json.createObjectNode();
      assignment.put("questionId", questionId);
      assignment.put("topicId", topicId);
      out.add(assignment);
    }
    return out;
  }

  // --- helpers de leitura tolerante -----------------------------------------------------------

  private String text(JsonNode source, String... keys) {
    for (String key : keys) {
      JsonNode node = source.get(key);
      if (node == null || node.isNull()) continue;
      String value = node.isValueNode() ? node.asText().trim() : "";
      if (!value.isEmpty()) return value;
    }
    return null;
  }

  private JsonNode first(JsonNode source, String... keys) {
    for (String key : keys) {
      JsonNode node = source.get(key);
      if (node != null && !node.isNull()) return node;
    }
    return null;
  }

  private String orDefault(String value, String fallback) {
    return value == null ? fallback : value;
  }

  private double number(JsonNode source, double fallback, String... keys) {
    JsonNode node = first(source, keys);
    if (node == null) return fallback;
    if (node.isNumber()) return node.asDouble();
    try {
      return Double.parseDouble(node.asText().replace(',', '.'));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private double weight(ObjectNode source) {
    double value = number(source, 1, "weight", "peso", "weightPct");
    return value <= 0 ? 1 : value;
  }

  private double clamp(double value, int min, int max) {
    if (Double.isNaN(value)) return min;
    return Math.max(min, Math.min((int) value, max));
  }

  private String idOrSlug(ObjectNode source, String name, String... keys) {
    String id = text(source, keys);
    return id == null ? slug(name) : id;
  }

  /** Slug estável para quando o modelo não manda id: o front usa como âncora de rota. */
  static String slug(String value) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
    String ascii = normalized.replaceAll("\\p{M}", "");
    String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    slug = slug.replaceAll("^-+|-+$", "");
    return slug.isEmpty() ? "topico" : slug;
  }
}
