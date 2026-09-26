package br.com.ciclo.identity.presentation;

import br.com.ciclo.identity.application.IdentityApplicationService;
import br.com.ciclo.identity.application.IdentityApplicationService.*;
import br.com.ciclo.identity.domain.IdentitySettings;
import br.com.ciclo.identity.domain.User;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class IdentityController {
  private final IdentityApplicationService identity;

  public IdentityController(IdentityApplicationService identity) {
    this.identity = identity;
  }

  @PostMapping("/api/v1/auth/register")
  ResponseEntity<Registration> register(@Valid @RequestBody RegisterRequest r) {
    return ResponseEntity.status(201)
        .body(identity.register(r.email(), r.displayName(), r.password(), r.workspaceName()));
  }

  @PostMapping("/api/v1/auth/verify-email")
  ResponseEntity<Void> verify(@Valid @RequestBody TokenRequest r) {
    identity.verifyEmail(r.token());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/v1/auth/login")
  Auth login(@Valid @RequestBody LoginRequest r, HttpServletResponse response) {
    Auth auth = identity.login(r.email(), r.password());
    cookies(response, auth);
    return auth;
  }

  @PostMapping("/api/v1/auth/refresh")
  Auth refresh(
      @CookieValue(name = "ciclo_refresh", required = false) String cookie,
      @RequestBody(required = false) RefreshRequest body,
      HttpServletResponse response) {
    String token = cookie != null ? cookie : body == null ? null : body.refreshToken();
    Auth auth = identity.refresh(token);
    cookies(response, auth);
    return auth;
  }

  @PostMapping("/api/v1/auth/logout")
  ResponseEntity<Void> logout(
      @CookieValue(name = "ciclo_refresh", required = false) String token,
      HttpServletResponse response) {
    identity.logout(token);
    expire(response);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/v1/auth/forgot-password")
  ResponseEntity<Void> forgot(@Valid @RequestBody EmailRequest r) {
    identity.forgotPassword(r.email());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/api/v1/auth/reset-password")
  ResponseEntity<Void> reset(@Valid @RequestBody ResetRequest r) {
    identity.resetPassword(r.token(), r.password());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/v1/me")
  UserView me(@AuthenticationPrincipal Jwt jwt) {
    return identity.getUser(UUID.fromString(jwt.getSubject()));
  }

  @GetMapping("/api/admin/v1/users")
  @PreAuthorize("hasRole('ADMIN')")
  IdentityPortsPage users(
      @RequestParam(defaultValue = "") String search,
      @RequestParam(required = false) User.Status status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var found = identity.listUsers(search, status, page, size);
    return new IdentityPortsPage(
        found.items().stream()
            .map(u -> new UserView(u.id(), u.email(), u.displayName(), u.role(), u.status()))
            .toList(),
        found.total(),
        page,
        size);
  }

  @PatchMapping("/api/admin/v1/users/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  UserView update(@PathVariable UUID id, @RequestBody UpdateUserRequest r) {
    return identity.updateUser(id, r.displayName(), r.status());
  }

  @PostMapping("/api/admin/v1/users/{id}/revoke-sessions")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> revoke(@PathVariable UUID id) {
    identity.revokeSessions(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/admin/v1/users/{id}/confirm-email")
  @PreAuthorize("hasRole('ADMIN')")
  UserView confirmEmail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return identity.confirmEmail(id, jwt.getSubject());
  }

  @PostMapping("/api/admin/v1/users/{id}/resend-verification")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> resendVerification(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    identity.resendVerification(id, jwt.getSubject());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/admin/v1/identity/settings")
  @PreAuthorize("hasRole('ADMIN')")
  IdentitySettings identitySettings() {
    return identity.identitySettings();
  }

  @PutMapping("/api/admin/v1/identity/settings")
  @PreAuthorize("hasRole('ADMIN')")
  IdentitySettings updateIdentitySettings(
      @Valid @RequestBody IdentitySettingsRequest request, @AuthenticationPrincipal Jwt jwt) {
    return identity.updateIdentitySettings(request.emailVerificationRequired(), jwt.getSubject());
  }

  @GetMapping("/api/admin/v1/identity/mail")
  @PreAuthorize("hasRole('ADMIN')")
  br.com.ciclo.identity.application.IdentityPorts.MailConfiguration mailConfiguration() {
    return identity.mailConfiguration();
  }

  @PostMapping("/api/admin/v1/identity/mail/test")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> testMail(@Valid @RequestBody EmailRequest request) {
    identity.testMail(request.email());
    return ResponseEntity.noContent().build();
  }

  private void cookies(HttpServletResponse response, Auth auth) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        cookie("ciclo_access", auth.accessToken(), Duration.ofMinutes(15), "/").toString());
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        cookie("ciclo_refresh", auth.refreshToken(), Duration.ofDays(30), "/api/v1/auth")
            .toString());
  }

  private void expire(HttpServletResponse response) {
    response.addHeader(
        HttpHeaders.SET_COOKIE, cookie("ciclo_access", "", Duration.ZERO, "/").toString());
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        cookie("ciclo_refresh", "", Duration.ZERO, "/api/v1/auth").toString());
  }

  private ResponseCookie cookie(String name, String value, Duration maxAge, String path) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(false)
        .sameSite("Lax")
        .path(path)
        .maxAge(maxAge)
        .build();
  }

  record RegisterRequest(
      @Email String email,
      @Size(min = 2, max = 120) String displayName,
      @Size(min = 10, max = 200) String password,
      @Size(min = 2, max = 100) String workspaceName) {}

  record LoginRequest(@Email String email, @NotBlank String password) {}

  record TokenRequest(@NotBlank String token) {}

  record RefreshRequest(String refreshToken) {}

  record EmailRequest(@NotBlank @Email String email) {}

  record ResetRequest(@NotBlank String token, @Size(min = 10, max = 200) String password) {}

  record UpdateUserRequest(String displayName, User.Status status) {}

  record IdentitySettingsRequest(boolean emailVerificationRequired) {}

  record IdentityPortsPage(java.util.List<UserView> items, long total, int page, int size) {}
}
