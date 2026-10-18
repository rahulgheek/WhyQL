package com.whyql.whyql.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.whyql.whyql.auth.domain.UserStatus;
import com.whyql.whyql.auth.repository.OAuthAccountRepository;
import com.whyql.whyql.auth.repository.UserRepository;
import com.whyql.whyql.auth.service.AuthErrorCode;
import com.whyql.whyql.auth.service.AuthException;
import com.whyql.whyql.auth.service.AuthService;

/**
 * Full auth flow against real Postgres + Redis (Testcontainers). Outbound mail is a
 * mock bean so the OTP code is captured from the SimpleMailMessage without SMTP.
 */
@SpringBootTest
@Import({AuthTestcontainersConfiguration.class, AuthServiceIntegrationTest.TestConfig.class})
class AuthServiceIntegrationTest {

  private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    JavaMailSender mailSender() {
      return mock(JavaMailSender.class);
    }
  }

  @DynamicPropertySource
  static void testSecrets(DynamicPropertyRegistry registry) {
    registry.add("JWT_SECRET", () -> "test-secret-012345678901234567890123456789012345678901234");
    registry.add("ENCRYPTION_KEY", () -> "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=");
  }

  @Autowired
  AuthService auth;

  @Autowired
  UserRepository users;

  @Autowired
  OAuthAccountRepository oauthAccounts;

  @Autowired
  StringRedisTemplate redis;

  @Autowired
  JavaMailSender mailSender;

  @BeforeEach
  void reset() {
    oauthAccounts.deleteAll();
    users.deleteAll();
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    doNothing().when(mailSender).send(any(SimpleMailMessage.class));
  }

  /** Returns the 6-digit OTP captured from the last email sent by the service. */
  private String capturedOtp(ArgumentCaptor<SimpleMailMessage> captor) {
    verify(mailSender, atLeastOnce()).send(captor.capture());
    SimpleMailMessage message = captor.getValue();
    assertThat(message.getSubject()).contains("verification code");
    Matcher matcher = SIX_DIGITS.matcher(message.getText());
    assertThat(matcher.find()).as("OTP code not found in email: " + message.getText()).isTrue();
    return matcher.group(1);
  }

  private static void assertCode(Throwable t, AuthErrorCode expected) {
    assertThat(t).isInstanceOf(AuthException.class);
    assertThat(((AuthException) t).code()).isEqualTo(expected);
  }

  @Test
  void happyPathRegisterVerifyLoginRefreshLogout() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

    AuthService.UserDto registered = auth.register("Alice@example.com", "Str0ng!Pass");
    assertThat(registered.email()).isEqualTo("alice@example.com");
    assertThat(registered.status()).isEqualTo(UserStatus.PENDING);
    assertThat(users.existsByEmail("alice@example.com")).isTrue();

    // A wrong code increments the attempt counter but the real code still works afterward.
    assertThatThrownBy(() -> auth.verifyOtp("alice@example.com", "000000", "127.0.0.1"))
        .satisfies(t -> assertCode(t, AuthErrorCode.INVALID_OTP));

    String code = capturedOtp(captor);
    auth.verifyOtp("Alice@example.com", code, "127.0.0.1");
    assertThat(users.findByEmail("alice@example.com").orElseThrow().getStatus())
        .isEqualTo(UserStatus.VERIFIED);

    AuthService.TokenPair tokens = auth.login("alice@example.com", "Str0ng!Pass");
    assertThat(tokens.accessToken()).isNotBlank();
    assertThat(tokens.refreshToken()).isNotBlank();
    assertThat(tokens.expiresInSeconds()).isEqualTo(15 * 60);

    // Refresh rotates the refresh token; the old one is consumed and can no longer be used.
    // (Access tokens may be byte-identical when issued within the same second: JWT iat/exp
    // have second granularity, so rotation is proven by the refresh token, not the access token.)
    AuthService.TokenPair rotated = auth.refresh(tokens.refreshToken());
    assertThat(rotated.refreshToken()).isNotEqualTo(tokens.refreshToken());
    assertThatThrownBy(() -> auth.refresh(tokens.refreshToken()))
        .satisfies(t -> assertCode(t, AuthErrorCode.INVALID_REFRESH_TOKEN));

    auth.logout(rotated.refreshToken());
    assertThatThrownBy(() -> auth.refresh(rotated.refreshToken()))
        .satisfies(t -> assertCode(t, AuthErrorCode.INVALID_REFRESH_TOKEN));
  }

  @Test
  void loginRejectsUnverifiedAndWrongPassword() {
    auth.register("bob@example.com", "Str0ng!Pass");

    assertThatThrownBy(() -> auth.login("bob@example.com", "Str0ng!Pass"))
        .satisfies(t -> assertCode(t, AuthErrorCode.ACCOUNT_NOT_VERIFIED));

    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    String code = capturedOtp(captor);
    auth.verifyOtp("bob@example.com", code, "127.0.0.1");

    assertThatThrownBy(() -> auth.login("bob@example.com", "Wrong!Pass"))
        .satisfies(t -> assertCode(t, AuthErrorCode.INVALID_CREDENTIALS));
    assertThatThrownBy(() -> auth.login("nope@example.com", "Str0ng!Pass"))
        .satisfies(t -> assertCode(t, AuthErrorCode.INVALID_CREDENTIALS));
  }

  @Test
  void fiveFailedAttemptsLockTheCode() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    auth.register("carol@example.com", "Str0ng!Pass");
    String realCode = capturedOtp(captor); // valid code that we intentionally never use

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> auth.verifyOtp("carol@example.com", "000000", "127.0.0.1"))
          .satisfies(t -> assertCode(t, AuthErrorCode.INVALID_OTP));
    }

    // Even the correct code is rejected once the attempt limit is reached.
    assertThatThrownBy(() -> auth.verifyOtp("carol@example.com", realCode, "127.0.0.1"))
        .satisfies(t -> assertCode(t, AuthErrorCode.OTP_ATTEMPTS_EXCEEDED));
  }

  @Test
  void resendLimitPerEmailAndPerIp() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    auth.register("dave@example.com", "Str0ng!Pass");
    capturedOtp(captor); // first code comes from register

    // Per-email limit: three resends go through, the fourth is blocked.
    auth.resendOtp("dave@example.com", "10.0.0.1");
    capturedOtp(captor);
    auth.resendOtp("dave@example.com", "10.0.0.2");
    capturedOtp(captor);
    auth.resendOtp("dave@example.com", "10.0.0.3");
    capturedOtp(captor);

    assertThatThrownBy(() -> auth.resendOtp("dave@example.com", "10.0.0.4"))
        .satisfies(t -> assertCode(t, AuthErrorCode.RESEND_LIMIT_EXCEEDED));

    // Per-IP limit: three different emails resend once from the same IP, the fourth is blocked
    // even though no single email hit its own limit.
    for (int i = 1; i <= 3; i++) {
      auth.register("ipuser" + i + "@example.com", "Str0ng!Pass");
      capturedOtp(captor);
      auth.resendOtp("ipuser" + i + "@example.com", "10.9.9.9");
      capturedOtp(captor);
    }
    auth.register("ipuser4@example.com", "Str0ng!Pass");
    capturedOtp(captor);
    assertThatThrownBy(() -> auth.resendOtp("ipuser4@example.com", "10.9.9.9"))
        .satisfies(t -> assertCode(t, AuthErrorCode.RESEND_LIMIT_EXCEEDED));
  }

  @Test
  void duplicateEmailRejected() {
    auth.register("eve@example.com", "Str0ng!Pass");
    assertThatThrownBy(() -> auth.register("EVE@example.com", "Other!Pass1"))
        .satisfies(t -> assertCode(t, AuthErrorCode.USER_ALREADY_EXISTS));
  }

  @Test
  void oauthCreatesVerifiedUserForUnknownEmail() {
    AuthService.TokenPair tokens = auth.loginWithOAuth("github", "gh-123", "grace@example.com");

    assertThat(tokens.accessToken()).isNotBlank();
    assertThat(users.findByEmail("grace@example.com").orElseThrow().getStatus())
        .isEqualTo(UserStatus.VERIFIED);
    assertThat(oauthAccounts.findByProviderAndProviderUserId("github", "gh-123")).isPresent();

    // Second login with the same provider account reuses the link (no duplicate user).
    AuthService.TokenPair again = auth.loginWithOAuth("github", "gh-123", "grace@example.com");
    assertThat(again.accessToken()).isNotBlank();
    assertThat(users.count()).isEqualTo(1);
  }

  @Test
  void oauthAutoLinksExistingEmailAndVerifiesPendingAccount() {
    auth.register("heidi@example.com", "Str0ng!Pass");
    assertThat(users.findByEmail("heidi@example.com").orElseThrow().getStatus())
        .isEqualTo(UserStatus.PENDING);

    AuthService.TokenPair tokens = auth.loginWithOAuth("google", "goog-456", "Heidi@Example.com");

    assertThat(tokens.accessToken()).isNotBlank();
    assertThat(users.findByEmail("heidi@example.com").orElseThrow().getStatus())
        .isEqualTo(UserStatus.VERIFIED);
    assertThat(oauthAccounts.findByProviderAndProviderUserId("google", "goog-456")).isPresent();

    // The original password still works alongside the linked provider.
    AuthService.TokenPair passwordLogin = auth.login("heidi@example.com", "Str0ng!Pass");
    assertThat(passwordLogin.accessToken()).isNotBlank();
  }

  @Test
  void oauthOnlyAccountRejectsPasswordLogin() {
    auth.loginWithOAuth("github", "gh-789", "ivan@example.com");

    assertThatThrownBy(() -> auth.login("ivan@example.com", "whatever-pass"))
        .satisfies(t -> assertCode(t, AuthErrorCode.INVALID_CREDENTIALS));
  }

  @Test
  void oauthRejectsUnsupportedProviderAndMissingEmail() {
    assertThatThrownBy(() -> auth.loginWithOAuth("facebook", "fb-1", "judy@example.com"))
        .satisfies(t -> assertCode(t, AuthErrorCode.OAUTH_PROVIDER_UNSUPPORTED));

    assertThatThrownBy(() -> auth.loginWithOAuth("github", "gh-unknown", null))
        .satisfies(t -> assertCode(t, AuthErrorCode.OAUTH_EMAIL_MISSING));
  }
}
