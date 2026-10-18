package com.whyql.whyql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import com.whyql.whyql.shared.JwtService;

/**
 * Plain unit test (no Spring context): token creation/verification, tamper rejection,
 * wrong-secret rejection, and the minimum-secret-length guard.
 */
class JwtServiceTest {

  private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";

  private final JwtService jwt = new JwtService(SECRET);

  @Test
  void accessTokenContainsExpectedClaimsAndFifteenMinuteTtl() {
    String token = jwt.createAccessToken("user-123", "alice@example.com");
    DecodedJWT decoded = jwt.verifyAccessToken(token);

    assertThat(decoded.getSubject()).isEqualTo("user-123");
    assertThat(decoded.getClaim("email").asString()).isEqualTo("alice@example.com");
    assertThat(decoded.getClaim("typ").asString()).isEqualTo("access");
    assertThat(decoded.getIssuer()).isEqualTo("whyql");

    java.time.Instant now = java.time.Instant.now();
    assertThat(decoded.getExpiresAt().toInstant()).isAfter(now.plusSeconds(14 * 60));
    assertThat(decoded.getExpiresAt().toInstant()).isBefore(now.plusSeconds(16 * 60));
  }

  @Test
  void tamperedTokenIsRejected() {
    String token = jwt.createAccessToken("user-123", "alice@example.com");
    String tampered = token.substring(0, token.length() - 5) + "XXXXX";
    assertThatThrownBy(() -> jwt.verifyAccessToken(tampered))
        .isInstanceOf(JWTVerificationException.class);
  }

  @Test
  void tokenSignedWithDifferentSecretIsRejected() {
    JwtService other = new JwtService("different-secret-0123456789012345678901234567890123456789");
    String token = jwt.createAccessToken("user-123", "alice@example.com");
    assertThatThrownBy(() -> other.verifyAccessToken(token))
        .isInstanceOf(JWTVerificationException.class);
  }

  @Test
  void shortSecretFailsFast() {
    assertThatThrownBy(() -> new JwtService("short")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new JwtService("")).isInstanceOf(IllegalStateException.class);
  }
}
