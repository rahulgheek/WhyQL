package com.whyql.whyql.auth.store;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * TTL-bound OTPs: key whyql:otp:{email}, TTL 5 minutes. A stale key means an expired or
 * already-consumed code, which the service treats as OTP_EXPIRED.
 */
@Repository
public class RedisOtpStore implements OtpStore {

  private static final String KEY_PREFIX = "whyql:otp:";
  private static final Duration TTL = Duration.ofMinutes(5);

  private final StringRedisTemplate redis;

  public RedisOtpStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void storeOtpHash(String email, String otpHash) {
    redis.opsForValue().set(key(email), otpHash, TTL);
  }

  @Override
  public Optional<String> getOtpHash(String email) {
    return Optional.ofNullable(redis.opsForValue().get(key(email)));
  }

  @Override
  public void deleteOtpHash(String email) {
    redis.delete(key(email));
  }

  private static String key(String email) {
    return KEY_PREFIX + email.toLowerCase();
  }
}
