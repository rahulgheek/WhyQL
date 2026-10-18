package com.whyql.whyql.auth.store;

import java.util.Optional;

/**
 * One-time password hashes in Redis. Only the BCrypt hash of a 6-digit code is kept,
 * with a 5-minute TTL. Never the plaintext code.
 */
public interface OtpStore {

  void storeOtpHash(String email, String otpHash);

  Optional<String> getOtpHash(String email);

  void deleteOtpHash(String email);
}
