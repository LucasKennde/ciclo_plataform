package br.com.ciclo.admin.application;

import br.com.ciclo.admin.domain.PlatformSettings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public final class AdminPorts {
  private AdminPorts() {}

  public interface Repository {
    PlatformSettings settings();

    PlatformSettings saveSettings(PlatformSettings value, String actor, Instant updatedAt);

    void audit(String action, String subject, String detail, String actor, Instant createdAt);

    List<Audit> audit(int limit);
  }

  public interface Events {
    void settingsChanged(boolean maintenanceMode);
  }

  public interface IdentityClient {
    OptionalLong userCount(String authorization);
  }

  public interface AiClient {
    Optional<AiSummary> usageSummary(String authorization, int hours);
  }

  public record Dashboard(long users, AiSummary ai, Instant generatedAt) {}

  public record AiSummary(
      long requests, long totalTokens, double estimatedCostUsd, long blocked, long errors) {
    public static AiSummary empty() {
      return new AiSummary(0, 0, 0, 0, 0);
    }
  }

  public record Audit(
      long id, String action, String subject, String detail, String actor, Instant createdAt) {}
}
