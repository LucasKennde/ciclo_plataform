package br.com.ciclo.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.ciclo.identity.application.IdentityPorts.AccountTokens;
import br.com.ciclo.identity.application.IdentityPorts.Events;
import br.com.ciclo.identity.application.IdentityPorts.Mail;
import br.com.ciclo.identity.application.IdentityPorts.Passwords;
import br.com.ciclo.identity.application.IdentityPorts.Sessions;
import br.com.ciclo.identity.application.IdentityPorts.Settings;
import br.com.ciclo.identity.application.IdentityPorts.Tokens;
import br.com.ciclo.identity.application.IdentityPorts.Users;
import br.com.ciclo.identity.application.IdentityPorts.Workspaces;
import br.com.ciclo.identity.domain.IdentitySettings;
import br.com.ciclo.identity.domain.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityApplicationServiceTest {
  @Mock Users users;
  @Mock Workspaces workspaces;
  @Mock Passwords passwords;
  @Mock Tokens tokens;
  @Mock Sessions sessions;
  @Mock AccountTokens accountTokens;
  @Mock Mail mail;
  @Mock Settings settings;
  @Mock Events events;

  private IdentityApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new IdentityApplicationService(
            users, workspaces, passwords, tokens, sessions, accountTokens, mail, settings, events);
  }

  @Test
  void createsAnActiveUserWithoutSendingMailWhenVerificationIsDisabled() {
    UUID workspaceId = UUID.randomUUID();
    when(settings.get()).thenReturn(new IdentitySettings(false, Instant.now(), "system"));
    when(users.findByEmail("student@example.com")).thenReturn(Optional.empty());
    when(passwords.hash("Password123")).thenReturn("hash");
    when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(workspaces.create(eq("Meu espaço"), any(UUID.class))).thenReturn(workspaceId);

    var result = service.register("Student@Example.com", "Student", "Password123", "Meu espaço");

    assertThat(result.emailVerificationRequired()).isFalse();
    assertThat(result.workspaceId()).isEqualTo(workspaceId);
    verify(users)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                user -> user.status() == User.Status.ACTIVE && user.verifiedAt() == null));
    verifyNoInteractions(accountTokens, mail);
  }

  @Test
  void createsAPendingUserAndSendsMailWhenVerificationIsRequired() {
    when(settings.get()).thenReturn(new IdentitySettings(true, Instant.now(), "admin"));
    when(users.findByEmail("student@example.com")).thenReturn(Optional.empty());
    when(passwords.hash("Password123")).thenReturn("hash");
    when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(workspaces.create(eq("Meu espaço"), any(UUID.class))).thenReturn(UUID.randomUUID());
    when(tokens.randomToken()).thenReturn("raw-token");
    when(tokens.hashToken("raw-token")).thenReturn("hashed-token");

    var result = service.register("student@example.com", "Student", "Password123", "Meu espaço");

    assertThat(result.emailVerificationRequired()).isTrue();
    verify(accountTokens)
        .create(
            any(UUID.class),
            eq(AccountTokens.Kind.VERIFY_EMAIL),
            eq("hashed-token"),
            any(Instant.class));
    verify(mail).verification("student@example.com", "Student", "raw-token");
  }

  @Test
  void confirmsPendingUserAndConsumesVerificationTokensAtomically() {
    User user = User.register("student@example.com", "Student", "hash", true);
    when(users.findById(user.id())).thenReturn(Optional.of(user));
    when(users.saveAndConsumeVerificationTokens(eq(user), any(Instant.class))).thenReturn(user);

    var result = service.confirmEmail(user.id(), "admin-id");

    assertThat(result.status()).isEqualTo(User.Status.ACTIVE);
    assertThat(user.verifiedAt()).isNotNull();
    verify(events)
        .audit("USER_EMAIL_CONFIRMED", user.id().toString(), "Confirmação manual", "admin-id");
  }

  @Test
  void refusesToConfirmSuspendedUser() {
    User user = User.register("student@example.com", "Student", "hash", false);
    user.suspend();
    when(users.findById(user.id())).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.confirmEmail(user.id(), "admin-id"))
        .isInstanceOf(IdentityApplicationService.Conflict.class);

    verify(users, never()).saveAndConsumeVerificationTokens(any(), any());
  }

  @Test
  void resendingVerificationReplacesPreviousTokens() {
    User user = User.register("student@example.com", "Student", "hash", true);
    when(users.findById(user.id())).thenReturn(Optional.of(user));
    when(tokens.randomToken()).thenReturn("new-token");
    when(tokens.hashToken("new-token")).thenReturn("new-hash");

    service.resendVerification(user.id(), "admin-id");

    verify(accountTokens)
        .replace(
            eq(user.id()),
            eq(AccountTokens.Kind.VERIFY_EMAIL),
            eq("new-hash"),
            any(Instant.class),
            any(Instant.class));
    verify(mail).verification(user.email(), user.displayName(), "new-token");
  }
}
