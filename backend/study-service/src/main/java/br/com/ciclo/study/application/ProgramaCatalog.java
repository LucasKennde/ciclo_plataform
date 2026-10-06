package br.com.ciclo.study.application;

import br.com.ciclo.study.application.StudyPorts.*;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conteúdo programático oficial, transcrito do edital e versionado no repositório.
 *
 * <p>Existe porque a rota por IA depende de credencial e de o modelo acertar: sem chave não há
 * edital, e com chave o resultado muda a cada execução. O catálogo é o texto do Anexo III, literal,
 * então o mesmo concurso nasce igual em qualquer máquina. A extração por IA continua valendo para
 * edital que ainda não foi transcrito.
 */
public class ProgramaCatalog {
  private final JsonMapper json;
  private final Fonte fonte;
  private final List<SyllabusSubject> basicas;
  private final Map<String, Programa> programas = new LinkedHashMap<>();

  public ProgramaCatalog(JsonMapper json, String path) {
    this.json = json;
    JsonNode root = read(path);
    this.fonte = fonte(root.path("fonte"));
    this.basicas = subjects(root.path("materiasBasicas"));
    for (JsonNode node : root.path("programas")) {
      Programa p = programa(node);
      programas.put(p.slug(), p);
    }
    if (basicas.isEmpty() || programas.isEmpty())
      throw new IllegalStateException(
          "Catálogo de programas vazio: " + path + " não traz o conteúdo programático do edital.");
  }

  public Fonte fonte() {
    return fonte;
  }

  public List<String> slugs() {
    return List.copyOf(programas.keySet());
  }

  public List<ProgramaView> programas() {
    return programas.values().stream()
        .map(p -> new ProgramaView(p.slug(), p.nome(), p.topicos().size(), p.total(), p.weight()))
        .toList();
  }

  /**
   * Monta o edital do concurso: as matérias básicas, comuns a todo candidato, mais o programa do
   * cargo. É o que a extração por IA produziria, sem depender de chave nem de modelo.
   */
  public List<SyllabusSubject> subjectsFor(String slug) {
    Programa programa = programas.get(slug);
    if (programa == null)
      throw new IllegalArgumentException(
          "O catálogo não tem o programa \""
              + slug
              + "\". Disponível: "
              + String.join(", ", programas.keySet())
              + ".");
    List<SyllabusSubject> out = new ArrayList<>(basicas);
    out.add(
        new SyllabusSubject(
            programa.slug(), programa.nome(), programa.weight(), programa.topicos()));
    return out;
  }

  /**
   * Casa o cargo digitado na criação do concurso com um programa, só quando a correspondência é
   * exata. Cargo ambíguo ou ausente devolve null de propósito: sugerir o programa errado semeia o
   * edital inteiro errado, e o usuário não teria como perceber.
   */
  public String suggestForRole(String role) {
    String alvo = normaliza(role);
    if (alvo.isEmpty()) return null;
    String achado = null;
    for (Programa p : programas.values()) {
      if (normaliza(p.nome()).equals(alvo)) {
        if (achado != null) return null; // ambiguidade: não chuta
        achado = p.slug();
      }
    }
    return achado;
  }

  private static String normaliza(String s) {
    if (s == null) return "";
    return Normalizer.normalize(s, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase()
        .replaceAll("[^a-z0-9]+", " ")
        .trim();
  }

  // --- leitura do arquivo programas/*.json ------------------------------------------------------
  // Jackson 3 falha em propriedade desconhecida e o arquivo tem metadados que o dominio ignora,
  // então o parse é manual por JsonNode em vez de reflexao sobre record.

  private Fonte fonte(JsonNode n) {
    return new Fonte(
        n.path("orgao").asString(""),
        n.path("edital").asString(""),
        n.path("publicacao").asString(""),
        n.path("anexo").asString(""),
        n.path("questoesP1").asInt(0),
        n.path("questoesP2").asInt(0),
        n.path("transcricao").asString(""));
  }

  private Programa programa(JsonNode n) {
    return new Programa(
        n.path("slug").asString(""),
        n.path("name").asString(""),
        n.path("weight").asDouble(1),
        topicos(n.path("topics")));
  }

  private List<SyllabusSubject> subjects(JsonNode arr) {
    List<SyllabusSubject> out = new ArrayList<>();
    for (JsonNode n : arr) {
      out.add(
          new SyllabusSubject(
              n.path("id").asString(""),
              n.path("name").asString(""),
              n.path("weight").asDouble(1),
              topicos(n.path("topics"))));
    }
    return out;
  }

  private List<SyllabusTopic> topicos(JsonNode arr) {
    List<SyllabusTopic> out = new ArrayList<>();
    for (JsonNode n : arr) {
      JsonNode ev = n.path("evidence");
      Evidence evidencia =
          ev.isMissingNode() || ev.isNull()
              ? null
              : new Evidence(
                  ev.path("page").isNull() ? null : ev.path("page").asInt(),
                  ev.path("excerpt").asString(""),
                  ev.path("confidence").asDouble(1));
      out.add(
          new SyllabusTopic(
              n.path("id").asString(""),
              n.path("name").asString(""),
              n.path("weight").asDouble(1),
              evidencia,
              topicos(n.path("children"))));
    }
    return out;
  }

  private JsonNode read(String path) {
    // application não pode depender de Spring (regra do ArchitectureTest): o classpath é lido
    // direto, e quem decide o caminho é a configuração.
    try (InputStream in = ProgramaCatalog.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null)
        throw new IllegalStateException(
            "Catálogo de programas não encontrado no classpath: " + path + ".");
      return json.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Não consegui ler o catálogo de programas em " + path + ".", e);
    }
  }

  public record Fonte(
      String orgao,
      String edital,
      String publicacao,
      String anexo,
      int questoesP1,
      int questoesP2,
      String transcricao) {}

  public record ProgramaView(
      String slug, String name, int topicos, int subtopicos, double weight) {}

  private record Programa(String slug, String nome, double weight, List<SyllabusTopic> topicos) {
    int total() {
      return total(topicos);
    }

    private static int total(List<SyllabusTopic> ns) {
      int n = 0;
      for (SyllabusTopic t : ns) n += 1 + total(t.children());
      return n;
    }
  }
}
