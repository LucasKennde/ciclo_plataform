package br.com.ciclo.identity.infrastructure.security;

import br.com.ciclo.identity.application.IdentityPorts.Passwords;
import br.com.ciclo.identity.application.IdentityPorts.Tokens;
import br.com.ciclo.identity.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class SecurityAdapters implements Passwords, Tokens {
  private final PasswordEncoder passwordEncoder;
  private final JwtEncoder jwtEncoder;

  public SecurityAdapters(PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder) {
    this.passwordEncoder = passwordEncoder;
    this.jwtEncoder = jwtEncoder;
  }

  public String hash(String raw) {
    return passwordEncoder.encode(raw);
  }

  public boolean matches(String raw, String hash) {
    return passwordEncoder.matches(raw, hash);
  }

  public String accessToken(User user, UUID workspaceId) {
    Instant now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("ciclo-identity")
            .issuedAt(now)
            .expiresAt(now.plus(15, ChronoUnit.MINUTES))
            .subject(user.id().toString())
            .claim("workspace_id", workspaceId.toString())
            .claim("roles", java.util.List.of(user.role().name()))
            .build();
    return jwtEncoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  public String randomToken() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String hashToken(String token) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static SecretKey key(String configured) {
    byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32)
      throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }
}
