package br.com.ciclo.identity.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {
  @Id public UUID id;

  @Column(nullable = false, unique = true, length = 190)
  public String email;

  @Column(name = "display_name", nullable = false, length = 120)
  public String displayName;

  @Column(name = "password_hash", nullable = false)
  public String passwordHash;

  @Column(nullable = false, length = 30)
  public String role;

  @Column(nullable = false, length = 40)
  public String status;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "verified_at")
  public Instant verifiedAt;

  protected UserEntity() {}
}
