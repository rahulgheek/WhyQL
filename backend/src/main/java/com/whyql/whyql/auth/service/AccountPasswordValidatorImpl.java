package com.whyql.whyql.auth.service;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.whyql.whyql.auth.repository.UserRepository;
import com.whyql.whyql.datasource.service.AccountPasswordValidator;

/**
 * Auth-side implementation of the reveal gate: bcrypt compare against the
 * user's stored password hash. The key is the JWT subject (user id) — the
 * same id data sources are owned by; emails are NOT stable identity keys
 * here (the JWT carries the id, not the email).
 *
 * OAuth-only accounts (no local password) report {@code hasPassword = false}
 * so the UI can offer the adopt-a-password flow instead of accepting a
 * doomed guess.
 */
@Component
public class AccountPasswordValidatorImpl implements AccountPasswordValidator {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public AccountPasswordValidatorImpl(UserRepository userRepository,
      PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public boolean hasPassword(String userId) {
    return findUser(userId)
        .map(u -> u.getPasswordHash() != null && !u.getPasswordHash().isBlank())
        .orElse(false);
  }

  @Override
  public boolean matches(String userId, String rawPassword) {
    return findUser(userId)
        .map(u -> u.getPasswordHash() != null
            && rawPassword != null
            && passwordEncoder.matches(rawPassword, u.getPasswordHash()))
        .orElse(false);
  }

  private java.util.Optional<com.whyql.whyql.auth.domain.User> findUser(String userId) {
    try {
      return userRepository.findById(UUID.fromString(userId));
    } catch (IllegalArgumentException badId) {
      // Not a user id (defensive): no user can be resolved.
      return java.util.Optional.empty();
    }
  }
}
