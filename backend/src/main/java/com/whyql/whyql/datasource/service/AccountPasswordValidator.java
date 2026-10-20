package com.whyql.whyql.datasource.service;

/**
 * Seam for verifying the caller's WhyQL account password without the
 * datasource module knowing anything about auth internals. Implemented by
 * the auth module (bcrypt over {@code users.password_hash}).
 *
 * Used by the credential-reveal gate: decompressing a stored database
 * password requires re-entering the account password. {@code userId} is the
 * JWT subject (the applications' stable owner id), not an email.
 */
public interface AccountPasswordValidator {

  /** Does this account have a local password at all (OAuth-only = false)? */
  boolean hasPassword(String userId);

  /** bcrypt match against the stored hash; false when unsigned/no hash. */
  boolean matches(String userId, String rawPassword);
}
