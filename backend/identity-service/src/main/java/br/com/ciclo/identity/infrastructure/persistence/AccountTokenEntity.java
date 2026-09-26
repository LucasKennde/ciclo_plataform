package br.com.ciclo.identity.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_tokens")
public class AccountTokenEntity {
  @Id public UUID id;

  @Column(name = "user_id", nullable = false)
  public UUID userId;

  @Column(nullable = false, length = 30)
  public String kind;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  public String tokenHash;

  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;

  @Column(name = "consumed_at")
  public Instant consumedAt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  protected AccountTokenEntity() {}
}
