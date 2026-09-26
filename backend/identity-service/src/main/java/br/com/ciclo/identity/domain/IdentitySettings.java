package br.com.ciclo.identity.domain;

import java.time.Instant;
import java.util.Objects;

public record IdentitySettings(
    boolean emailVerificationRequired, Instant updatedAt, String updatedBy) {
  public IdentitySettings {
    Objects.requireNonNull(updatedAt);
    if (updatedBy == null || updatedBy.isBlank()) {
      throw new IllegalArgumentException("Responsável pela alteração é obrigatório.");
    }
  }
}
