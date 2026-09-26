package br.com.ciclo.identity.application;

import br.com.ciclo.identity.domain.IdentitySettings;
import br.com.ciclo.identity.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class IdentityPorts {
  private IdentityPorts() {}

  public interface Users {
    Optional<User> findByEmail(String email);

    Optional<User> findById(UUID id);

    User save(User user);

    User saveAndConsumeVerificationTokens(User user, Instant consumedAt);

    Page list(String search, User.Status status, int page, int size);

    record Page(List<User> items, long total) {}
  }

  public interface Workspaces {
    UUID create(String name, UUID ownerId);

    Optional<UUID> findPrimaryForUser(UUID userId);
  }

  public interface Passwords {
    String hash(String raw);

    boolean matches(String raw, String hash);
  }

  public interface Tokens {
    String accessToken(User user, UUID workspaceId);

    String randomToken();

    String hashToken(String token);
  }

  public interface Sessions {
    void create(UUID id, UUID userId, String tokenHash, Instant expiresAt);

    Optional<Session> findByHash(String hash);

    void consumeAndRotate(UUID currentId, UUID nextId, String nextHash, Instant nextExpiry);

    void revokeForUser(UUID userId);

    void revokeByHash(String hash);

    record Session(UUID id, UUID userId, Instant expiresAt, Instant consumedAt, Instant revokedAt) {
      public boolean valid(Instant now) {
        return consumedAt == null && revokedAt == null && expiresAt.isAfter(now);
      }
    }
  }

  public interface AccountTokens {
    void create(UUID userId, Kind kind, String hash, Instant expiresAt);

    Optional<Entry> find(String hash, Kind kind);

    void consume(UUID id);

    void replace(UUID userId, Kind kind, String hash, Instant expiresAt, Instant replacedAt);

    enum Kind {
      VERIFY_EMAIL,
      RESET_PASSWORD
    }

    record Entry(UUID id, UUID userId, Instant expiresAt, Instant consumedAt) {
      public boolean valid(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
      }
    }
  }

  public interface Mail {
    void verification(String email, String name, String token);

    void passwordReset(String email, String name, String token);

    void test(String recipient);

    MailConfiguration configuration();
  }

  public interface Settings {
    IdentitySettings get();

    IdentitySettings save(boolean emailVerificationRequired, String actor, Instant updatedAt);
  }

  public interface Events {
    void audit(String action, String subject, String detail, String actor);
  }

  public record MailConfiguration(
      String mode,
      boolean configured,
      String host,
      int port,
      String usernameMasked,
      String from,
      boolean authentication,
      boolean startTls) {}
}
