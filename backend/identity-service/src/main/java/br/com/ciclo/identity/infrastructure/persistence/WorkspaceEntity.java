package br.com.ciclo.identity.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspaces")
public class WorkspaceEntity {
  @Id public UUID id;

  @Column(nullable = false, length = 100)
  public String name;

  @Column(name = "owner_id", nullable = false)
  public UUID ownerId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  protected WorkspaceEntity() {}
}
