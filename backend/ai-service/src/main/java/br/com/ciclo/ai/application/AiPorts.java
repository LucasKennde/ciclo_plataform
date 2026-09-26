package br.com.ciclo.ai.application;

import br.com.ciclo.ai.domain.*;
import br.com.ciclo.ai.domain.AiCatalog.*;
import java.time.*;
import java.util.*;

public final class AiPorts {
  private AiPorts() {}

  public interface Repository {
    Optional<Credential> credential(Provider p);

    void saveCredential(Credential c);

    void deleteCredential(Provider p);

    Map<Operation, Route> routes();

    void saveRoute(Route route, String actor);

    AiPolicy policy();

    void savePolicy(AiPolicy policy, String actor);

    void usage(Usage usage);

    Summary summary(Instant from);

    List<Usage> events(int limit);

    List<Consumer> consumers(Instant from);

    List<Block> blocks();

    Optional<Block> activeBlock(String principal, Instant now);

    void block(Block block);

    void unblock(String principal);

    void audit(String action, String subject, String detail, String actor);

    List<Audit> audit(int limit);
  }

  public interface Cipher {
    String encrypt(String plain, Provider provider);

    String decrypt(String encrypted, Provider provider);

    boolean available();
  }

  public interface ProviderGateway {
    Result execute(
        Provider provider,
        String apiKey,
        String model,
        Operation operation,
        Map<String, Object> input);

    TestResult test(Provider provider, String apiKey);
  }

  public interface Counters {
    Decision allow(String principal, AiPolicy policy);

    void addTokens(String principal, long tokens);
  }

  public interface Objects {
    byte[] get(String key);
  }

  public interface Events {
    void completed(UUID jobId, Map<String, Object> result);

    void failed(UUID jobId, String code, String message);
  }

  public record Credential(
      Provider provider,
      String encryptedKey,
      String last4,
      Instant updatedAt,
      String updatedBy,
      Boolean lastTestOk,
      String lastTestError) {}

  public record Route(
      Operation operation,
      Provider provider,
      String model,
      double inputPrice,
      double outputPrice) {}

  public record Result(Map<String, Object> output, long inputTokens, long outputTokens) {
    public Result {
      output = output == null ? Map.of() : Map.copyOf(output);
    }
  }

  public record TestResult(boolean ok, String error) {}

  public record Decision(boolean allowed, String reason) {}

  public record Usage(
      UUID id,
      UUID requestId,
      String principal,
      Operation operation,
      Provider provider,
      String model,
      String outcome,
      String ruleCode,
      long inputTokens,
      long outputTokens,
      double costUsd,
      long durationMs,
      Instant createdAt) {}

  public record Summary(
      long requests, long totalTokens, double estimatedCostUsd, long blocked, long errors) {}

  public record Consumer(
      String principal, long requests, long totalTokens, double costUsd, long blocked) {}

  public record Block(
      String principal, String reason, Instant blockedUntil, String createdBy, Instant createdAt) {
    public boolean active(Instant now) {
      return blockedUntil == null || blockedUntil.isAfter(now);
    }
  }

  public record Audit(
      long id, String action, String subject, String detail, String actor, Instant createdAt) {}
}
