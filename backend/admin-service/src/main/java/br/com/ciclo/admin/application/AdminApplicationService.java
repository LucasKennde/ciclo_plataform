package br.com.ciclo.admin.application;

import br.com.ciclo.admin.application.AdminPorts.*;
import br.com.ciclo.admin.domain.PlatformSettings;
import java.time.Instant;
import java.util.List;

public class AdminApplicationService {
  private final Repository repository;
  private final Events events;
  private final IdentityClient identity;
  private final AiClient ai;

  public AdminApplicationService(
      Repository repository, Events events, IdentityClient identity, AiClient ai) {
    this.repository = repository;
    this.events = events;
    this.identity = identity;
    this.ai = ai;
  }

  public PlatformSettings settings() {
    return repository.settings();
  }

  public PlatformSettings update(PlatformSettings value, String actor) {
    var updated = repository.saveSettings(value, actor, Instant.now());
    repository.audit("SETTINGS_CHANGED", "platform", value.toString(), actor, Instant.now());
    events.settingsChanged(value.maintenanceMode());
    return updated;
  }

  public Dashboard dashboard(String authorization) {
    long users = identity.userCount(authorization).orElse(0);
    AiSummary usage = ai.usageSummary(authorization, 24).orElseGet(AiSummary::empty);
    return new Dashboard(users, usage, Instant.now());
  }

  public List<Audit> audit() {
    return repository.audit(100);
  }
}
