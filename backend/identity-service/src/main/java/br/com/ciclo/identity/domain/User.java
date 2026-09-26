package br.com.ciclo.identity.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class User {
  public enum Role {
    STUDENT,
    ADMIN
  }

  public enum Status {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED
  }

  private final UUID id;
  private final String email;
  private String displayName;
  private String passwordHash;
  private final Role role;
  private Status status;
  private final Instant createdAt;
  private Instant verifiedAt;

  public User(
      UUID id,
      String email,
      String displayName,
      String passwordHash,
      Role role,
      Status status,
      Instant createdAt,
      Instant verifiedAt) {
    this.id = Objects.requireNonNull(id);
    this.email = normalizeEmail(email);
    this.displayName = requireName(displayName);
    this.passwordHash = Objects.requireNonNull(passwordHash);
    this.role = Objects.requireNonNull(role);
    this.status = Objects.requireNonNull(status);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.verifiedAt = verifiedAt;
  }

  public static User register(String email, String displayName, String passwordHash) {
    return register(email, displayName, passwordHash, true);
  }

  public static User register(
      String email, String displayName, String passwordHash, boolean verificationRequired) {
    Instant now = Instant.now();
    return new User(
        UUID.randomUUID(),
        email,
        displayName,
        passwordHash,
        Role.STUDENT,
        verificationRequired ? Status.PENDING_VERIFICATION : Status.ACTIVE,
        now,
        null);
  }

  public void verify(Instant now) {
    if (status == Status.SUSPENDED) throw new IllegalStateException("Conta suspensa.");
    if (status == Status.ACTIVE) return;
    status = Status.ACTIVE;
    verifiedAt = now;
  }

  public void suspend() {
    if (role == Role.ADMIN)
      throw new IllegalStateException("Administradores não podem ser suspensos por este fluxo.");
    status = Status.SUSPENDED;
  }

  public void activate() {
    status = Status.ACTIVE;
  }

  public void rename(String value) {
    displayName = requireName(value);
  }

  public void changePassword(String hash) {
    passwordHash = Objects.requireNonNull(hash);
  }

  public boolean canAuthenticate() {
    return status == Status.ACTIVE;
  }

  public UUID id() {
    return id;
  }

  public String email() {
    return email;
  }

  public String displayName() {
    return displayName;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public Role role() {
    return role;
  }

  public Status status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant verifiedAt() {
    return verifiedAt;
  }

  private static String normalizeEmail(String value) {
    if (value == null || !value.contains("@"))
      throw new IllegalArgumentException("E-mail inválido.");
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static String requireName(String value) {
    if (value == null || value.trim().length() < 2)
      throw new IllegalArgumentException("Nome inválido.");
    return value.trim();
  }
}
