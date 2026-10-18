package com.whyql.whyql.auth.store;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Key: whyql:refresh:{sha256(token)}, value: user ID, TTL 30 days.
 * SHA-256 is fine here because the token itself has 256 bits of entropy; the hash protects
 * the token in case Redis contents are exposed.
 */
@Repository
public class RedisRefreshTokenStore implements RefreshTokenStore {

  private static final String KEY_PREFIX = "whyql:refresh:";
  private static final Duration TTL = Duration.ofDays(30);

  private final StringRedisTemplate redis;

  public RedisRefreshTokenStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public String create(String userId) {
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes())
        + Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes());
    redis.opsForValue().set(key(token), userId, TTL);
    return token;
  }

  @Override
  public Optional<String> consume(String plainToken) {
    return Optional.ofNullable(redis.opsForValue().getAndDelete(key(plainToken)));
  }

  @Override
  public void revoke(String plainToken) {
    redis.delete(key(plainToken));
  }

  private static String key(String plainToken) {
    return KEY_PREFIX + sha256(plainToken);
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
