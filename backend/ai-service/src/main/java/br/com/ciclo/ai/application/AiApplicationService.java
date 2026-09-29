package br.com.ciclo.ai.application;

import br.com.ciclo.ai.application.AiPorts.*;
import br.com.ciclo.ai.domain.*;
import br.com.ciclo.ai.domain.AiCatalog.*;
import java.time.*;
import java.util.*;

public class AiApplicationService {
  private final Repository repo;
  private final Cipher cipher;
  private final ProviderGateway providers;
  private final Counters counters;
  private final AiPorts.Objects objects;
  private final Events events;
  private final Map<Provider, String> envKeys;

  public AiApplicationService(
      Repository repo,
      Cipher cipher,
      ProviderGateway providers,
      Counters counters,
      AiPorts.Objects objects,
      Events events,
      Map<Provider, String> envKeys) {
    this.repo = repo;
    this.cipher = cipher;
    this.providers = providers;
    this.counters = counters;
    this.objects = objects;
    this.events = events;
    this.envKeys = envKeys;
  }

  public Overview overview() {
    Map<Provider, List<AiCatalog.Model>> byProvider = new EnumMap<>(Provider.class);
    for (Provider p : Provider.values()) byProvider.put(p, new ArrayList<>());
    for (AiCatalog.Model model : repo.models()) byProvider.get(model.provider()).add(model);

    List<ProviderView> list =
        Arrays.stream(Provider.values())
            .map(
                p -> {
                  var saved = repo.credential(p);
                  String env = envKeys.get(p);
                  return new ProviderView(
                      p,
                      p.displayName(),
                      saved.isPresent() || env != null,
                      saved.isPresent() ? "PANEL" : env != null ? "ENV" : null,
                      saved
                          .map(Credential::last4)
                          .orElse(
                              env == null ? null : env.substring(Math.max(0, env.length() - 4))),
                      saved.map(Credential::updatedAt).orElse(null),
                      saved.map(Credential::lastTestOk).orElse(null),
                      saved.map(Credential::lastTestError).orElse(null),
                      List.copyOf(byProvider.get(p)));
                })
            .toList();
    List<OperationView> operations =
        Arrays.stream(Operation.values())
            .map(op -> new OperationView(op, op.displayName()))
            .toList();
    // Só o que está gravado em ai_routes: antes, defaults() inventava uma rota OpenAI para todas
    // as operações e a tela mostrava "configurado" para um roteamento que ninguém criou.
    return new Overview(cipher.available(), list, operations, repo.routes());
  }

  public ProviderView setKey(Provider provider, String raw, String actor, boolean force) {
    String key = normalize(raw);
    // A chave mestre é o que realmente impede a gravação. Checar antes de gastar uma chamada de
    // rede com o provedor evita o ciclo de "testou, falhou, salva mesmo assim, falhou de novo".
    if (!cipher.available())
      throw new IllegalStateException(
          "AI_KEYS_MASTER_KEY não configurada (32 bytes em Base64). Sem ela a credencial não pode"
              + " ser gravada.");
    var tested = providers.test(provider, key);
    if (!tested.ok() && !force) throw new IllegalArgumentException(tested.error());
    repo.saveCredential(
        new Credential(
            provider,
            cipher.encrypt(key, provider),
            key.substring(Math.max(0, key.length() - 4)),
            Instant.now(),
            actor,
            tested.ok(),
            tested.error()));
    repo.audit("KEY_SET", provider.name(), tested.ok() ? "validada" : "salva com override", actor);
    return overview().providers().stream()
        .filter(p -> p.id() == provider)
        .findFirst()
        .orElseThrow();
  }

  public void removeKey(Provider provider, String actor) {
    boolean inUse = repo.routes().values().stream().anyMatch(r -> r.provider() == provider);
    if (inUse && envKeys.get(provider) == null)
      throw new IllegalStateException(
          "Troque as rotas que utilizam este provedor antes de remover a credencial.");
    repo.deleteCredential(provider);
    repo.audit("KEY_REMOVED", provider.name(), null, actor);
  }

  public TestResult test(Provider provider) {
    return providers.test(provider, key(provider));
  }

  public Overview registerModel(ModelInput input, String actor) {
    Provider provider = parseProvider(input.provider());
    // A validação de id, label, preço e operações vive no domínio e vale para todo caminho
    // de entrada, inclusive o seed do banco.
    var model =
        new AiCatalog.Model(
            provider,
            input.id(),
            input.label(),
            input.inputUsdPerMillion(),
            input.outputUsdPerMillion(),
            parseOperations(input.operations()),
            Instant.now(),
            actor);
    repo.saveModel(model, actor);
    repo.audit("MODEL_SAVED", provider + "/" + model.id(), model.label(), actor);
    return overview();
  }

  public Overview removeModel(String providerRaw, String modelId, String actor) {
    Provider provider = parseProvider(providerRaw);
    if (modelId == null || modelId.isBlank())
      throw new IllegalArgumentException("Informe o id do modelo.");
    boolean inUse =
        repo.routes().values().stream().anyMatch(r -> matches(r, provider, modelId.trim()));
    if (inUse)
      throw new IllegalStateException(
          "Troque as rotas que utilizam o modelo " + modelId + " antes de removê-lo.");
    if (!repo.deleteModel(provider, modelId.trim()))
      throw new IllegalArgumentException(
          "Modelo não encontrado para " + provider.displayName() + ".");
    repo.audit("MODEL_REMOVED", provider + "/" + modelId.trim(), null, actor);
    return overview();
  }

  public Overview updateRoutes(List<RouteInput> inputs, String actor) {
    if (inputs == null || inputs.isEmpty())
      throw new IllegalArgumentException("Informe ao menos uma rota.");
    // Valida tudo antes de gravar: sem isso um lote parcialmente inválido deixaria as rotas
    // anteriores aplicadas e a tela mostraria um estado que ninguém escolheu.
    Map<Operation, AiCatalog.Model> planned = new LinkedHashMap<>();
    for (var input : inputs) {
      Provider provider = parseProvider(input == null ? null : input.provider());
      Operation operation = parseOperation(input == null ? null : input.operation());
      if (input == null || input.model() == null || input.model().isBlank())
        throw new IllegalArgumentException("Selecione o modelo da rota.");
      planned.put(operation, requireRegisteredModel(provider, input.model().trim(), operation));
    }
    for (var entry : planned.entrySet()) {
      var model = entry.getValue();
      keyOrThrow(model.provider());
      repo.saveRoute(
          new Route(
              entry.getKey(),
              model.provider(),
              model.id(),
              model.inputUsdPerMillion(),
              model.outputUsdPerMillion()),
          actor);
      repo.audit(
          "ROUTE_CHANGED", entry.getKey().name(), model.provider() + "/" + model.id(), actor);
    }
    return overview();
  }

  public void execute(
      UUID jobId, String principal, Operation operation, Map<String, Object> payload) {
    Instant started = Instant.now();
    UUID requestId = UUID.randomUUID();
    Route route = effectiveRoutes().get(operation);
    if (route == null) {
      events.failed(jobId, "AI_ROUTE_MISSING", "Nenhuma rota configurada para " + operation);
      return;
    }
    AiPolicy policy = repo.policy();
    var block = repo.activeBlock(principal, Instant.now());
    var decision =
        block.isPresent() ? new Decision(false, "MANUAL_BLOCK") : counters.allow(principal, policy);
    if (policy.killSwitch()) decision = new Decision(false, "KILL_SWITCH");
    if (!decision.allowed()) {
      repo.usage(
          new Usage(
              UUID.randomUUID(),
              requestId,
              principal,
              operation,
              route.provider(),
              route.model(),
              "BLOCKED",
              decision.reason(),
              0,
              0,
              0,
              Duration.between(started, Instant.now()).toMillis(),
              Instant.now()));
      events.failed(jobId, decision.reason(), "Execução de IA bloqueada pela política.");
      return;
    }
    try {
      Map<String, Object> input = new LinkedHashMap<>(payload);
      Object objectKey = payload.get("objectKey");
      if (objectKey != null && !objectKey.toString().isBlank()) {
        byte[] file = objects.get(objectKey.toString());
        input.put("fileBase64", Base64.getEncoder().encodeToString(file));
      }
      Result result =
          providers.execute(
              route.provider(), key(route.provider()), route.model(), operation, input);
      long total = result.inputTokens() + result.outputTokens();
      counters.addTokens(principal, total);
      double cost =
          result.inputTokens() / 1_000_000d * route.inputPrice()
              + result.outputTokens() / 1_000_000d * route.outputPrice();
      repo.usage(
          new Usage(
              UUID.randomUUID(),
              requestId,
              principal,
              operation,
              route.provider(),
              route.model(),
              "COMPLETED",
              null,
              result.inputTokens(),
              result.outputTokens(),
              cost,
              Duration.between(started, Instant.now()).toMillis(),
              Instant.now()));
      events.completed(jobId, result.output());
    } catch (Exception e) {
      repo.usage(
          new Usage(
              UUID.randomUUID(),
              requestId,
              principal,
              operation,
              route.provider(),
              route.model(),
              "ERROR",
              null,
              0,
              0,
              0,
              Duration.between(started, Instant.now()).toMillis(),
              Instant.now()));
      events.failed(jobId, "PROVIDER_ERROR", safe(e.getMessage()));
    }
  }

  public Dashboard dashboard(int hours) {
    Instant from = Instant.now().minus(Duration.ofHours(Math.max(1, Math.min(hours, 24 * 90))));
    return new Dashboard(
        repo.summary(from), repo.events(100), repo.consumers(from), repo.blocks(), repo.policy());
  }

  public AiPolicy updatePolicy(AiPolicy policy, String actor) {
    repo.savePolicy(policy, actor);
    repo.audit("POLICY_CHANGED", "global", policy.toString(), actor);
    return policy;
  }

  public void block(String principal, String reason, Long durationSeconds, String actor) {
    if (principal == null || principal.isBlank() || reason == null || reason.isBlank())
      throw new IllegalArgumentException("Principal e motivo são obrigatórios.");
    repo.block(
        new Block(
            principal,
            reason,
            durationSeconds == null ? null : Instant.now().plusSeconds(durationSeconds),
            actor,
            Instant.now()));
    repo.audit("PRINCIPAL_BLOCKED", principal, reason, actor);
  }

  public void unblock(String principal, String actor) {
    repo.unblock(principal);
    repo.audit("PRINCIPAL_UNBLOCKED", principal, null, actor);
  }

  public List<Audit> audit() {
    return repo.audit(50);
  }

  private Map<Operation, Route> effectiveRoutes() {
    return repo.routes();
  }

  private AiCatalog.Model requireRegisteredModel(Provider provider, String modelId, Operation op) {
    return repo.models().stream()
        .filter(m -> m.provider() == provider && m.id().equals(modelId))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "O modelo "
                        + modelId
                        + " não está cadastrado em "
                        + provider.displayName()
                        + ". Cadastre-o em Modelos antes de aplicar a rota."));
  }

  private static boolean matches(Route route, Provider provider, String modelId) {
    return route.provider() == provider && route.model() != null && route.model().equals(modelId);
  }

  private static Provider parseProvider(String raw) {
    if (raw == null || raw.isBlank())
      throw new IllegalArgumentException("Selecione o provedor do modelo.");
    try {
      return Provider.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Provedor desconhecido: " + raw + ".");
    }
  }

  private static Operation parseOperation(String raw) {
    if (raw == null || raw.isBlank())
      throw new IllegalArgumentException("Selecione a operação da rota.");
    try {
      return Operation.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Operação desconhecida: " + raw + ".");
    }
  }

  private static Set<Operation> parseOperations(List<String> raw) {
    if (raw == null || raw.isEmpty())
      throw new IllegalArgumentException("Marque ao menos uma operação para o modelo.");
    EnumSet<Operation> out = EnumSet.noneOf(Operation.class);
    for (String value : raw) {
      if (value == null || value.isBlank()) continue;
      Operation op = parseOperation(value);
      if (!out.add(op)) throw new IllegalArgumentException("Operação duplicada: " + value + ".");
    }
    return out;
  }

  private String key(Provider provider) {
    var saved = repo.credential(provider);
    if (saved.isPresent()) return cipher.decrypt(saved.get().encryptedKey(), provider);
    String env = envKeys.get(provider);
    if (env == null || env.isBlank())
      throw new IllegalStateException("Credencial não configurada para " + provider);
    return env;
  }

  private void keyOrThrow(Provider provider) {
    if (repo.credential(provider).isPresent() || envKeys.get(provider) != null) return;
    throw new IllegalStateException(
        "Salve a credencial de "
            + provider.displayName()
            + " antes de apontar uma rota para este provedor.");
  }

  private static String normalize(String raw) {
    if (raw == null) throw new IllegalArgumentException("Informe a API key.");
    String value = raw.trim().replaceFirst("(?i)^Bearer\\s+", "");
    if (value.length() < 8
        || value.length() > 512
        || value.chars().anyMatch(Character::isWhitespace))
      throw new IllegalArgumentException("API key inválida.");
    return value;
  }

  private static String safe(String value) {
    return value == null
        ? "Falha desconhecida."
        : value.substring(0, Math.min(500, value.length()));
  }

  public record ProviderView(
      Provider id,
      String label,
      boolean configured,
      String source,
      String last4,
      Instant updatedAt,
      Boolean lastTestOk,
      String lastTestError,
      List<AiCatalog.Model> models) {}

  public record OperationView(Operation id, String label) {}

  public record Overview(
      boolean encryptionAvailable,
      List<ProviderView> providers,
      List<OperationView> operations,
      Map<Operation, Route> routing) {}

  public record RouteInput(String operation, String provider, String model) {}

  public record ModelInput(
      String provider,
      String id,
      String label,
      double inputUsdPerMillion,
      double outputUsdPerMillion,
      List<String> operations) {}

  public record Dashboard(
      Summary summary,
      List<Usage> events,
      List<Consumer> consumers,
      List<Block> blocks,
      AiPolicy settings) {}
}
