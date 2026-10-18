package com.whyql.whyql.auth.store;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Atomic Redis counters. Key patterns:
 * whyql:otp:attempts:{email} (5 min TTL),
 * whyql:otp:resends:email:{email} and whyql:otp:resends:ip:{ip} (1 hour TTL).
 * The TTL is only set when the counter is created so successive increments do not extend
 * the lock window beyond the OTP lifetime / hour window.
 */
@Repository
public class RedisRateLimiter implements RateLimiter {

  private static final Duration ATTEMPT_TTL = Duration.ofMinutes(5);
  private static final Duration RESEND_TTL = Duration.ofHours(1);

  private final StringRedisTemplate redis;

  public RedisRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public int attempts(String email) {
    return intValue(redis.opsForValue().get(attemptsKey(email)));
  }

  @Override
  public int recordAttempt(String email) {
    String key = attemptsKey(email);
    Long count = redis.opsForValue().increment(key);
    if (count == 1) {
      redis.expire(key, ATTEMPT_TTL);
    }
    return count.intValue();
  }

  @Override
  public void resetAttempts(String email) {
    redis.delete(attemptsKey(email));
  }

  @Override
  public boolean canResend(String email, String ip) {
    return intValue(redis.opsForValue().get(resendEmailKey(email))) < 3
        && intValue(redis.opsForValue().get(resendIpKey(ip))) < 3;
  }

  @Override
  public void recordResend(String email, String ip) {
    incrementWithTtl(resendEmailKey(email), RESEND_TTL);
    incrementWithTtl(resendIpKey(ip), RESEND_TTL);
  }

  private static int intValue(String value) {
    return value == null ? 0 : Integer.parseInt(value);
  }

  private void incrementWithTtl(String key, Duration ttl) {
    Long count = redis.opsForValue().increment(key);
    if (count == 1) {
      redis.expire(key, ttl);
    }
  }

  private static String attemptsKey(String email) {
    return "whyql:otp:attempts:" + email.toLowerCase();
  }

  private static String resendEmailKey(String email) {
    return "whyql:otp:resends:email:" + email.toLowerCase();
  }

  private static String resendIpKey(String ip) {
    return "whyql:otp:resends:ip:" + ip;
  }
}
