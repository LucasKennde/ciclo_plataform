package br.com.ciclo.identity.application;

import br.com.ciclo.identity.application.IdentityPorts.*;
import br.com.ciclo.identity.domain.IdentitySettings;
import br.com.ciclo.identity.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public class IdentityApplicationService {
  private final Users users;
  private final Workspaces workspaces;
  private final Passwords passwords;
  private final Tokens tokens;
  private final Sessions sessions;
  private final AccountTokens accountTokens;
  private final Mail mail;
  private final Settings settings;
  private final Events events;

  public IdentityApplicationService(
      Users users,
      Workspaces workspaces,
      Passwords passwords,
      Tokens tokens,
      Sessions sessions,
      AccountTokens accountTokens,
      Mail mail,
      Settings settings,
      Events events) {
    this.users = users;
    this.workspaces = workspaces;
    this.passwords = passwords;
    this.tokens = tokens;
    this.sessions = sessions;
    this.accountTokens = accountTokens;
    this.mail = mail;
    this.settings = settings;
    this.events = events;
  }

  public Registration register(String email, String name, String password, String workspaceName) {
    validatePassword(password);
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    if (users.findByEmail(normalized).isPresent()) throw new Conflict("E-mail já cadastrado.");
    boolean verificationRequired = settings.get().emailVerificationRequired();
    User user =
        users.save(User.register(normalized, name, passwords.hash(password), verificationRequired));
    UUID workspaceId = workspaces.create(workspaceName, user.id());
    if (verificationRequired) sendVerification(user, false);
    return new Registration(user.id(), workspaceId, user.email(), verificationRequired);
  }

  public void verifyEmail(String raw) {
    var entry =
        accountTokens
            .find(tokens.hashToken(raw), AccountTokens.Kind.VERIFY_EMAIL)
            .filter(it -> it.valid(Instant.now()))
            .orElseThrow(() -> new Unauthorized("Token inválido ou expirado."));
    User user = requireUser(entry.userId());
    Instant now = Instant.now();
    user.verify(now);
    users.saveAndConsumeVerificationTokens(user, now);
  }

  public Auth login(String email, String password) {
    User user =
        users
            .findByEmail(email.trim().toLowerCase(Locale.ROOT))
            .filter(it -> passwords.matches(password, it.passwordHash()))
            .orElseThrow(() -> new Unauthorized("Credenciais inválidas."));
    if (!user.canAuthenticate())
      throw new Unauthorized(
          user.status() == User.Status.SUSPENDED
              ? "Conta suspensa."
              : "Confirme seu e-mail antes de entrar.");
    return issue(user);
  }

  public Auth refresh(String raw) {
    if (raw == null || raw.isBlank()) throw new Unauthorized("Sessão inválida.");
    String hash = tokens.hashToken(raw);
    var current =
        sessions
            .findByHash(hash)
            .filter(it -> it.valid(Instant.now()))
            .orElseThrow(() -> new Unauthorized("Sessão inválida."));
    User user = requireUser(current.userId());
    if (!user.canAuthenticate()) {
      sessions.revokeForUser(user.id());
      throw new Unauthorized("Conta indisponível.");
    }
    UUID workspaceId = workspaces.findPrimaryForUser(user.id()).orElseThrow();
    String nextRaw = tokens.randomToken();
    UUID nextId = UUID.randomUUID();
    sessions.consumeAndRotate(
        current.id(), nextId, tokens.hashToken(nextRaw), Instant.now().plus(Duration.ofDays(30)));
    return new Auth(
        tokens.accessToken(user, workspaceId),
        nextRaw,
        new UserView(user.id(), user.email(), user.displayName(), user.role(), user.status()),
        workspaceId);
  }

  public void logout(String refreshToken) {
    if (refreshToken != null && !refreshToken.isBlank())
      sessions.revokeByHash(tokens.hashToken(refreshToken));
  }

  public void forgotPassword(String email) {
    users
        .findByEmail(email.trim().toLowerCase(Locale.ROOT))
        .ifPresent(
            user -> {
              String raw = tokens.randomToken();
              accountTokens.create(
                  user.id(),
                  AccountTokens.Kind.RESET_PASSWORD,
                  tokens.hashToken(raw),
                  Instant.now().plus(Duration.ofMinutes(30)));
              mail.passwordReset(user.email(), user.displayName(), raw);
            });
  }

  public void resetPassword(String raw, String password) {
    validatePassword(password);
    var entry =
        accountTokens
            .find(tokens.hashToken(raw), AccountTokens.Kind.RESET_PASSWORD)
            .filter(it -> it.valid(Instant.now()))
            .orElseThrow(() -> new Unauthorized("Token inválido ou expirado."));
    User user = requireUser(entry.userId());
    user.changePassword(passwords.hash(password));
    users.save(user);
    accountTokens.consume(entry.id());
    sessions.revokeForUser(user.id());
  }

  public Users.Page listUsers(String search, User.Status status, int page, int size) {
    return users.list(
        search == null ? "" : search, status, Math.max(0, page), Math.min(100, Math.max(1, size)));
  }

  public UserView updateUser(UUID id, String name, User.Status status) {
    User user = requireUser(id);
    if (name != null) user.rename(name);
    if (status == User.Status.SUSPENDED) user.suspend();
    else if (status == User.Status.ACTIVE) user.activate();
    users.save(user);
    if (status == User.Status.SUSPENDED) sessions.revokeForUser(id);
    return view(user);
  }

  public IdentitySettings identitySettings() {
    return settings.get();
  }

  public IdentitySettings updateIdentitySettings(boolean verificationRequired, String actor) {
    IdentitySettings updated = settings.save(verificationRequired, actor, Instant.now());
    events.audit(
        "IDENTITY_SETTINGS_CHANGED",
        "identity",
        "emailVerificationRequired=" + verificationRequired,
        actor);
    return updated;
  }

  public MailConfiguration mailConfiguration() {
    return mail.configuration();
  }

  public void testMail(String recipient) {
    mail.test(recipient.trim().toLowerCase(Locale.ROOT));
  }

  public UserView confirmEmail(UUID id, String actor) {
    User user = requireUser(id);
    if (user.status() == User.Status.SUSPENDED)
      throw new Conflict("Uma conta suspensa não pode ser confirmada.");
    if (user.status() == User.Status.ACTIVE) return view(user);
    Instant now = Instant.now();
    user.verify(now);
    users.saveAndConsumeVerificationTokens(user, now);
    events.audit("USER_EMAIL_CONFIRMED", user.id().toString(), "Confirmação manual", actor);
    return view(user);
  }

  public void resendVerification(UUID id, String actor) {
    User user = requireUser(id);
    if (user.status() != User.Status.PENDING_VERIFICATION)
      throw new Conflict("Somente contas pendentes podem receber uma nova verificação.");
    sendVerification(user, true);
    events.audit(
        "USER_VERIFICATION_RESENT", user.id().toString(), "E-mail de verificação reenviado", actor);
  }

  public void revokeSessions(UUID id) {
    requireUser(id);
    sessions.revokeForUser(id);
  }

  public UserView getUser(UUID id) {
    return view(requireUser(id));
  }

  public void bootstrapAdmin(String email, String password) {
    if (email == null
        || email.isBlank()
        || password == null
        || password.isBlank()
        || users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).isPresent()) return;
    validatePassword(password);
    User admin =
        new User(
            UUID.randomUUID(),
            email,
            "Administrador",
            passwords.hash(password),
            User.Role.ADMIN,
            User.Status.ACTIVE,
            Instant.now(),
            Instant.now());
    users.save(admin);
    workspaces.create("Administração Ciclo", admin.id());
  }

  private void sendVerification(User user, boolean replaceExisting) {
    String raw = tokens.randomToken();
    Instant now = Instant.now();
    Instant expiresAt = now.plus(Duration.ofHours(24));
    if (replaceExisting) {
      accountTokens.replace(
          user.id(), AccountTokens.Kind.VERIFY_EMAIL, tokens.hashToken(raw), expiresAt, now);
    } else {
      accountTokens.create(
          user.id(), AccountTokens.Kind.VERIFY_EMAIL, tokens.hashToken(raw), expiresAt);
    }
    mail.verification(user.email(), user.displayName(), raw);
  }

  private Auth issue(User user) {
    UUID workspaceId = workspaces.findPrimaryForUser(user.id()).orElseThrow();
    String refresh = tokens.randomToken();
    sessions.create(
        UUID.randomUUID(),
        user.id(),
        tokens.hashToken(refresh),
        Instant.now().plus(Duration.ofDays(30)));
    return new Auth(tokens.accessToken(user, workspaceId), refresh, view(user), workspaceId);
  }

  private User requireUser(UUID id) {
    return users.findById(id).orElseThrow(() -> new NotFound("Usuário não encontrado."));
  }

  private UserView view(User user) {
    return new UserView(user.id(), user.email(), user.displayName(), user.role(), user.status());
  }

  private static void validatePassword(String value) {
    if (value == null
        || value.length() < 10
        || value.chars().noneMatch(Character::isDigit)
        || value.chars().noneMatch(Character::isUpperCase))
      throw new IllegalArgumentException(
          "A senha deve ter ao menos 10 caracteres, uma maiúscula e um número.");
  }

  public record Registration(
      UUID userId, UUID workspaceId, String email, boolean emailVerificationRequired) {}

  public record Auth(String accessToken, String refreshToken, UserView user, UUID workspaceId) {}

  public record UserView(
      UUID id, String email, String displayName, User.Role role, User.Status status) {}

  public static class Conflict extends RuntimeException {
    public Conflict(String m) {
      super(m);
    }
  }

  public static class MailUnavailable extends RuntimeException {
    public MailUnavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class Unauthorized extends RuntimeException {
    public Unauthorized(String m) {
      super(m);
    }
  }

  public static class NotFound extends RuntimeException {
    public NotFound(String m) {
      super(m);
    }
  }
}
