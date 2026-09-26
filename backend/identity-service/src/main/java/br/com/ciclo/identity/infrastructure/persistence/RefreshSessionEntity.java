package br.com.ciclo.identity.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions")
public class RefreshSessionEntity {
  @Id public UUID id;

  @Column(name = "user_id", nullable = false)
  public UUID userId;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  public String tokenHash;

  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;

  @Column(name = "consumed_at")
  public Instant consumedAt;

  @Column(name = "revoked_at")
  public Instant revokedAt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  protected RefreshSessionEntity() {}
}
