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
    Map<Operation, Route> routes = effectiveRoutes();
    List<ProviderView> list =
        Arrays.stream(Provider.values())
            .map(
                p -> {
                  var saved = repo.credential(p);
                  String env = envKeys.get(p);
                  return new ProviderView(
                      p,
                      p.name(),
                      saved.isPresent() || env != null,
                      saved.isPresent() ? "PANEL" : env != null ? "ENV" : null,
                      saved
                          .map(Credential::last4)
                          .orElse(
                              env == null ? null : env.substring(Math.max(0, env.length() - 4))),
                      saved.map(Credential::updatedAt).orElse(null),
                      saved.map(Credential::lastTestOk).orElse(null),
                      saved.map(Credential::lastTestError).orElse(null),
                      AiCatalog.MODELS.get(p));
                })
            .toList();
    return new Overview(cipher.available(), list, routes);
  }

  public ProviderView setKey(Provider provider, String raw, String actor, boolean force) {
    String key = normalize(raw);
    var tested = providers.test(provider, key);
    if (!tested.ok() && !force) throw new IllegalArgumentException(tested.error());
    if (!cipher.available()) throw new IllegalStateException("AI_KEYS_MASTER_KEY não configurada.");
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
    boolean inUse = effectiveRoutes().values().stream().anyMatch(r -> r.provider() == provider);
    if (inUse && envKeys.get(provider) == null)
      throw new IllegalStateException(
          "Troque as rotas que utilizam este provedor antes de remover a credencial.");
    repo.deleteCredential(provider);
    repo.audit("KEY_REMOVED", provider.name(), null, actor);
  }

  public TestResult test(Provider provider) {
    return providers.test(provider, key(provider));
  }

  public Overview updateRoutes(List<RouteInput> inputs, String actor) {
    for (var input : inputs) {
      Provider provider = Provider.valueOf(input.provider().toUpperCase());
      Operation operation = Operation.valueOf(input.operation().toUpperCase());
      var model = AiCatalog.requireModel(provider, input.model(), operation);
      key(provider);
      repo.saveRoute(
          new Route(
              operation,
              provider,
              model.id(),
              model.inputUsdPerMillion(),
              model.outputUsdPerMillion()),
          actor);
      repo.audit("ROUTE_CHANGED", operation.name(), provider + "/" + model.id(), actor);
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
    Map<Operation, Route> out = new EnumMap<>(Operation.class);
    out.putAll(defaults());
    out.putAll(repo.routes());
    return out;
  }

  private Map<Operation, Route> defaults() {
    var model = AiCatalog.MODELS.get(Provider.OPENAI).get(0);
    Map<Operation, Route> out = new EnumMap<>(Operation.class);
    for (Operation op : Operation.values())
      out.put(
          op,
          new Route(
              op,
              Provider.OPENAI,
              model.id(),
              model.inputUsdPerMillion(),
              model.outputUsdPerMillion()));
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

  public record Overview(
      boolean encryptionAvailable, List<ProviderView> providers, Map<Operation, Route> routing) {}

  public record RouteInput(String operation, String provider, String model) {}

  public record Dashboard(
      Summary summary,
      List<Usage> events,
      List<Consumer> consumers,
      List<Block> blocks,
      AiPolicy settings) {}
}
