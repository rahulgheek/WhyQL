package com.whyql.whyql.auth.domain;

/**
 * Account lifecycle. An account is PENDING until the email OTP is verified
 * (or a Google/GitHub login auto-verifies it), VERIFIED afterwards.
 * Login is only allowed for VERIFIED accounts.
 */
public enum UserStatus {
  PENDING,
  VERIFIED
}
