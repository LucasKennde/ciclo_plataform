package br.com.ciclo.admin.domain;

import java.time.Instant;

public record PlatformSettings(
    String productName,
    String supportEmail,
    String description,
    String locale,
    String timezone,
    boolean maintenanceMode,
    Instant updatedAt,
    String updatedBy) {
  public PlatformSettings {
    if (productName == null || productName.isBlank())
      throw new IllegalArgumentException("Nome do produto é obrigatório.");
    if (supportEmail == null || !supportEmail.contains("@"))
      throw new IllegalArgumentException("E-mail de suporte inválido.");
  }
}
