package com.whyql.whyql.shared;

import java.time.Instant;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * Issues and validates HS256 JWT access tokens. Claims: sub = user id, email, typ = access.
 * TTL is fixed at 15 minutes; refresh-token rotation happens in AuthService.
 * JWT_SECRET must be at least 32 characters or the application fails to start.
 *
 * Lives in the shared module root package so the auth module may depend on it
 * (Spring Modulith only exposes module-root package types across module boundaries).
 */
@Component
public class JwtService {

  private static final long ACCESS_TTL_SECONDS = 15 * 60;

  private final Algorithm algorithm;
  private final JWTVerifier verifier;

  public JwtService(@Value("${JWT_SECRET}") String secret) {
    if (secret == null || secret.isBlank() || secret.length() < 32) {
      throw new IllegalStateException("JWT_SECRET must be set and at least 32 characters long");
    }
    this.algorithm = Algorithm.HMAC256(secret);
    this.verifier = JWT.require(algorithm).withIssuer("whyql").build();
  }

  public String createAccessToken(String userId, String email) {
    Instant now = Instant.now();
    return JWT.create()
        .withIssuer("whyql")
        .withSubject(userId)
        .withClaim("email", email)
        .withClaim("typ", "access")
        .withIssuedAt(Date.from(now))
        .withExpiresAt(Date.from(now.plusSeconds(ACCESS_TTL_SECONDS)))
        .sign(algorithm);
  }

  /**
   * Verify and decode an access token. Throws JWTVerificationException (expired,
   * signature mismatch, unknown issuer) on any problem; callers map to error codes.
   */
  public DecodedJWT verifyAccessToken(String token) throws JWTVerificationException {
    return verifier.verify(token);
  }
}
