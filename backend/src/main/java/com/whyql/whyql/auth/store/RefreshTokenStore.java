package com.whyql.whyql.auth.store;

import java.util.Optional;

/**
 * Rotating, revocable refresh tokens. The presented token is hashed (SHA-256 over the
 * token bytes) and used as the Redis key, mapped to the owning user ID with a TTL.
 * Consume = read-and-delete; rotation deletes the old token and creates a new one.
 */
public interface RefreshTokenStore {

  /** Store a newly issued refresh token, return the plain token handed to the client. */
  String create(String userId);

  /** Consume a refresh token: return the owning user ID and remove the token. */
  Optional<String> consume(String plainToken);

  /** Revoke a refresh token (best effort, used by logout). */
  void revoke(String plainToken);
}
