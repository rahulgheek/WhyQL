package com.whyql.whyql.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.whyql.whyql.auth.store.RedisRateLimiter;

/**
 * Verifies the Redis key names, TTLs, and increment semantics of the rate limiter
 * against a mocked template — no real Redis needed for this contract test.
 */
class RedisRateLimiterTest {

  @Test
  @SuppressWarnings("unchecked")
  void attemptCounterExpiresAfterFiveMinutes() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment("whyql:otp:attempts:alice@example.com")).thenReturn(1L);

    RedisRateLimiter limiter = new RedisRateLimiter(redis);
    assertThat(limiter.recordAttempt("alice@example.com")).isEqualTo(1);
    verify(redis).expire("whyql:otp:attempts:alice@example.com", Duration.ofMinutes(5));
  }

  @Test
  @SuppressWarnings("unchecked")
  void attemptCounterDoesNotResetTtlOnFurtherIncrements() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);

    RedisRateLimiter limiter = new RedisRateLimiter(redis);
    limiter.recordAttempt("alice@example.com");
    limiter.recordAttempt("alice@example.com");
    limiter.recordAttempt("alice@example.com");
    verify(redis, times(1)).expire(anyString(), any(Duration.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void resendAllowedBelowLimitAndBlockedAtThree() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("whyql:otp:resends:email:alice@example.com")).thenReturn("0", "1", "2", "3");
    when(ops.get("whyql:otp:resends:ip:1.2.3.4")).thenReturn("0");

    RedisRateLimiter limiter = new RedisRateLimiter(redis);
    assertThat(limiter.canResend("alice@example.com", "1.2.3.4")).isTrue();
    assertThat(limiter.canResend("alice@example.com", "1.2.3.4")).isTrue();
    assertThat(limiter.canResend("alice@example.com", "1.2.3.4")).isTrue();
    assertThat(limiter.canResend("alice@example.com", "1.2.3.4")).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void recordResendIncrementsBothCountersWithOneHourTtl() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString())).thenReturn(1L);

    RedisRateLimiter limiter = new RedisRateLimiter(redis);
    limiter.recordResend("alice@example.com", "1.2.3.4");

    verify(ops).increment("whyql:otp:resends:email:alice@example.com");
    verify(ops).increment("whyql:otp:resends:ip:1.2.3.4");
    verify(redis).expire("whyql:otp:resends:email:alice@example.com", Duration.ofHours(1));
    verify(redis).expire("whyql:otp:resends:ip:1.2.3.4", Duration.ofHours(1));
  }

  @Test
  @SuppressWarnings("unchecked")
  void attemptsReadsZeroWhenCounterMissing() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenReturn(null);

    RedisRateLimiter limiter = new RedisRateLimiter(redis);
    assertThat(limiter.attempts("alice@example.com")).isZero();
  }
}
