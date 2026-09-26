package br.com.ciclo.shared.security;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedPrincipal(UUID userId, UUID workspaceId, Set<String> roles) {
  public boolean hasRole(String role) {
    return roles.contains(role);
  }
}
