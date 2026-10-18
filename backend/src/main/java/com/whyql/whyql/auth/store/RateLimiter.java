package com.whyql.whyql.auth.store;

/**
 * Redis-backed counters for OTP abuse protection: 5 verification attempts per code
 * (TTL aligned with the 5-minute OTP), and 3 resends per hour per email and per IP.
 */
public interface RateLimiter {

  /**
   * Current failed-attempt count for the email's active code.
   */
  int attempts(String email);

  /** Increment the failed-attempt counter (TTL 5 minutes), return the new count. */
  int recordAttempt(String email);

  /** Reset failed attempts after a successful verification or a resend. */
  void resetAttempts(String email);

  /**
   * True if a resend is allowed right now (both per-email and per-IP counters below 3).
   * Does not increment; call {@link #recordResend(String, String)} when the resend happens.
   */
  boolean canResend(String email, String ip);

  /** Increment both resend counters (TTL 1 hour). */
  void recordResend(String email, String ip);
}
