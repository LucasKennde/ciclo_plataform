package br.com.ciclo.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "identity_settings")
public class IdentitySettingsEntity {
  @Id public Short id;

  @Column(name = "email_verification_required", nullable = false)
  public boolean emailVerificationRequired;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  @Column(name = "updated_by", nullable = false, length = 100)
  public String updatedBy;

  protected IdentitySettingsEntity() {}
}
