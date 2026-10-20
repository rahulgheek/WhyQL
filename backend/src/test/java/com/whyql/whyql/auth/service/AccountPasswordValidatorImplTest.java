package com.whyql.whyql.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.whyql.whyql.auth.domain.User;
import com.whyql.whyql.auth.repository.UserRepository;

/**
 * The reveal gate resolves accounts by user id (the JWT subject), never by
 * email — a regression that email-looked-up a UUID must fail loudly here.
 */
class AccountPasswordValidatorImplTest {

  private UserRepository users;
  private PasswordEncoder encoder;
  private AccountPasswordValidatorImpl validator;

  private User user;
  private String userId;

  @BeforeEach
  void setUp() {
    users = Mockito.mock(UserRepository.class);
    encoder = Mockito.mock(PasswordEncoder.class);
    validator = new AccountPasswordValidatorImpl(users, encoder);

    user = new User();
    userId = UUID.randomUUID().toString();
    user.setId(UUID.fromString(userId));
    user.setEmail("someone@example.com");
    user.setPasswordHash("bcrypt-hash");

    lenient().when(users.findById(org.mockito.ArgumentMatchers.eq(UUID.fromString(userId))))
        .thenReturn(Optional.of(user));
    lenient().when(encoder.matches("right", "bcrypt-hash")).thenReturn(true);
    lenient().when(encoder.matches("wrong", "bcrypt-hash")).thenReturn(false);
  }

  @Test
  void resolvesByUserIdNotEmail() {
    assertTrue(validator.hasPassword(userId));
    assertTrue(validator.matches(userId, "right"));
    assertFalse(validator.matches(userId, "wrong"));
  }

  @Test
  void oauthOnlyAccountReportsNoPassword() {
    user.setPasswordHash(null);

    assertFalse(validator.hasPassword(userId));
    assertFalse(validator.matches(userId, "anything"));
  }

  @Test
  void unknownOrMalformedIdsFailClosed() {
    assertFalse(validator.hasPassword("not-a-uuid"));
    assertFalse(validator.hasPassword(UUID.randomUUID().toString()));
  }
}
