package br.com.ciclo.identity.infrastructure.persistence;

import br.com.ciclo.identity.application.IdentityPorts.*;
import br.com.ciclo.identity.domain.IdentitySettings;
import br.com.ciclo.identity.domain.User;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdentityPersistenceAdapter
    implements Users, Workspaces, Sessions, AccountTokens, Settings {
  private final UserJpaRepository users;
  private final WorkspaceJpaRepository workspaces;
  private final SessionJpaRepository sessions;
  private final AccountTokenJpaRepository accountTokens;
  private final IdentitySettingsJpaRepository settings;

  public IdentityPersistenceAdapter(
      UserJpaRepository users,
      WorkspaceJpaRepository workspaces,
      SessionJpaRepository sessions,
      AccountTokenJpaRepository accountTokens,
      IdentitySettingsJpaRepository settings) {
    this.users = users;
    this.workspaces = workspaces;
    this.sessions = sessions;
    this.accountTokens = accountTokens;
    this.settings = settings;
  }

  public Optional<User> findByEmail(String email) {
    return users.findByEmail(email).map(this::domain);
  }

  public Optional<User> findById(UUID id) {
    return users.findById(id).map(this::domain);
  }

  public User save(User user) {
    var e = users.findById(user.id()).orElseGet(UserEntity::new);
    e.id = user.id();
    e.email = user.email();
    e.displayName = user.displayName();
    e.passwordHash = user.passwordHash();
    e.role = user.role().name();
    e.status = user.status().name();
    e.createdAt = user.createdAt();
    e.verifiedAt = user.verifiedAt();
    users.save(e);
    return user;
  }

  @Transactional
  public User saveAndConsumeVerificationTokens(User user, Instant consumedAt) {
    save(user);
    accountTokens
        .findAllByUserIdAndKindAndConsumedAtIsNull(
            user.id(), AccountTokens.Kind.VERIFY_EMAIL.name())
        .forEach(
            token -> {
              token.consumedAt = consumedAt;
              accountTokens.save(token);
            });
    return user;
  }

  public Users.Page list(String search, User.Status status, int page, int size) {
    var result =
        users.findAll(
            (root, query, cb) -> {
              var predicates = new ArrayList<Predicate>();
              if (!search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(
                    cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("displayName")), like)));
              }
              if (status != null) predicates.add(cb.equal(root.get("status"), status.name()));
              return cb.and(predicates.toArray(Predicate[]::new));
            },
            PageRequest.of(page, size));
    return new Users.Page(
        result.getContent().stream().map(this::domain).toList(), result.getTotalElements());
  }

  public UUID create(String name, UUID ownerId) {
    var e = new WorkspaceEntity();
    e.id = UUID.randomUUID();
    e.name = name.trim();
    e.ownerId = ownerId;
    e.createdAt = Instant.now();
    workspaces.save(e);
    return e.id;
  }

  public Optional<UUID> findPrimaryForUser(UUID userId) {
    return workspaces.findFirstByOwnerId(userId).map(it -> it.id);
  }

  public void create(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
    var e = new RefreshSessionEntity();
    e.id = id;
    e.userId = userId;
    e.tokenHash = tokenHash;
    e.expiresAt = expiresAt;
    e.createdAt = Instant.now();
    sessions.save(e);
  }

  public Optional<Sessions.Session> findByHash(String hash) {
    return sessions
        .findByTokenHash(hash)
        .map(e -> new Sessions.Session(e.id, e.userId, e.expiresAt, e.consumedAt, e.revokedAt));
  }

  @Transactional
  public void consumeAndRotate(UUID currentId, UUID nextId, String nextHash, Instant nextExpiry) {
    var current = sessions.findById(currentId).orElseThrow();
    if (current.consumedAt != null || current.revokedAt != null)
      throw new IllegalStateException("Sessão já utilizada.");
    current.consumedAt = Instant.now();
    sessions.save(current);
    create(nextId, current.userId, nextHash, nextExpiry);
  }

  @Transactional
  public void revokeForUser(UUID userId) {
    sessions.findAll().stream()
        .filter(it -> it.userId.equals(userId) && it.revokedAt == null)
        .forEach(it -> it.revokedAt = Instant.now());
  }

  public void revokeByHash(String hash) {
    sessions
        .findByTokenHash(hash)
        .ifPresent(
            it -> {
              it.revokedAt = Instant.now();
              sessions.save(it);
            });
  }

  public void create(UUID userId, AccountTokens.Kind kind, String hash, Instant expiresAt) {
    var e = new AccountTokenEntity();
    e.id = UUID.randomUUID();
    e.userId = userId;
    e.kind = kind.name();
    e.tokenHash = hash;
    e.expiresAt = expiresAt;
    e.createdAt = Instant.now();
    accountTokens.save(e);
  }

  public Optional<AccountTokens.Entry> find(String hash, AccountTokens.Kind kind) {
    return accountTokens
        .findByTokenHashAndKind(hash, kind.name())
        .map(e -> new AccountTokens.Entry(e.id, e.userId, e.expiresAt, e.consumedAt));
  }

  public void consume(UUID id) {
    var e = accountTokens.findById(id).orElseThrow();
    e.consumedAt = Instant.now();
    accountTokens.save(e);
  }

  @Transactional
  public void replace(
      UUID userId, AccountTokens.Kind kind, String hash, Instant expiresAt, Instant replacedAt) {
    accountTokens
        .findAllByUserIdAndKindAndConsumedAtIsNull(userId, kind.name())
        .forEach(
            token -> {
              token.consumedAt = replacedAt;
              accountTokens.save(token);
            });
    create(userId, kind, hash, expiresAt);
  }

  public IdentitySettings get() {
    var value = settings.findById((short) 1).orElseThrow();
    return new IdentitySettings(value.emailVerificationRequired, value.updatedAt, value.updatedBy);
  }

  public IdentitySettings save(boolean emailVerificationRequired, String actor, Instant updatedAt) {
    var value = settings.findById((short) 1).orElseThrow();
    value.emailVerificationRequired = emailVerificationRequired;
    value.updatedAt = updatedAt;
    value.updatedBy = actor;
    settings.save(value);
    return new IdentitySettings(emailVerificationRequired, updatedAt, actor);
  }

  private User domain(UserEntity e) {
    return new User(
        e.id,
        e.email,
        e.displayName,
        e.passwordHash,
        User.Role.valueOf(e.role),
        User.Status.valueOf(e.status),
        e.createdAt,
        e.verifiedAt);
  }
}
