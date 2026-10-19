package com.whyql.whyql.auth.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whyql.whyql.auth.domain.OAuthAccount;
import com.whyql.whyql.auth.domain.User;
import com.whyql.whyql.auth.domain.UserStatus;
import com.whyql.whyql.auth.email.OtpEmailService;
import com.whyql.whyql.auth.email.OtpEmailService.AuthEmailException;
import com.whyql.whyql.auth.repository.OAuthAccountRepository;
import com.whyql.whyql.auth.repository.UserRepository;
import com.whyql.whyql.auth.store.OtpStore;
import com.whyql.whyql.auth.store.RateLimiter;
import com.whyql.whyql.auth.store.RefreshTokenStore;
import com.whyql.whyql.shared.JwtService;

/**
 * Auth operations: register, verifyOtp, resendOtp, login, refresh, logout,
 * plus OAuth login (loginWithOAuth) used by the Google/GitHub callback handler.
 *
 * Security notes:
 * - Only password/OTP hashes are persisted; plaintext codes and passwords are compared
 *   and discarded.
 * - Rate limits live in Redis counters (5 attempts per code, 3 resends/hour per email+IP).
 * - OAuth logins auto-link: a provider account whose email matches an existing user is
 *   linked to that user (and verifies a PENDING account, since the provider verified
 *   the email). A provider account with an unknown email creates a new VERIFIED user.
 * - IP is an argument so the controller passes the request's remote address; the
 *   service itself never reads the servlet request.
 */
@Service
public class AuthService {

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);

  private static final int PASSWORD_MIN_LENGTH = 8;
  private static final int OTP_ATTEMPTS_MAX = 5;
  private static final int ACCESS_TTL_SECONDS = 15 * 60;

  private final UserRepository userRepository;
  private final OAuthAccountRepository oauthAccountRepository;
  private final PasswordEncoder passwordEncoder;
  private final OtpStore otpStore;
  private final RateLimiter rateLimiter;
  private final RefreshTokenStore refreshTokenStore;
  private final JwtService jwtService;
  private final OtpEmailService emailService;

  public AuthService(UserRepository userRepository, OAuthAccountRepository oauthAccountRepository,
      PasswordEncoder passwordEncoder, OtpStore otpStore, RateLimiter rateLimiter,
      RefreshTokenStore refreshTokenStore, JwtService jwtService, OtpEmailService emailService) {
    this.userRepository = userRepository;
    this.oauthAccountRepository = oauthAccountRepository;
    this.passwordEncoder = passwordEncoder;
    this.otpStore = otpStore;
    this.rateLimiter = rateLimiter;
    this.refreshTokenStore = refreshTokenStore;
    this.jwtService = jwtService;
    this.emailService = emailService;
  }

  /** Public DTOs for callers (REST controllers now, GraphQL resolvers in Phase 6). No entity leaks out. */
  public record UserDto(UUID id, String email, UserStatus status) {}

  public record TokenPair(String accessToken, String refreshToken, int expiresInSeconds) {}

  /**
   * Create a PENDING account and send a 6-digit OTP. A verified email yields
   * USER_ALREADY_EXISTS. An existing but still-PENDING email is not an error:
   * the registration is treated as a resend (rate-limited like resendOtp) and
   * the submitted password replaces the pending one, so the user can simply
   * continue through verification instead of hitting a dead end.
   */
  @Transactional
  public UserDto register(String email, String password, String ip) {
    String normalized = normalizeEmail(email);
    if (normalized == null || !EMAIL_PATTERN.matcher(normalized).matches()) {
      throw new AuthException(AuthErrorCode.INVALID_EMAIL, "invalid email address");
    }
    if (password == null || password.trim().length() < PASSWORD_MIN_LENGTH) {
      throw new AuthException(AuthErrorCode.INVALID_PASSWORD,
          "password must be at least " + PASSWORD_MIN_LENGTH + " characters");
    }

    Optional<User> existing = userRepository.findByEmail(normalized);
    if (existing.isPresent()) {
      User user = existing.get();
      if (user.getStatus() == UserStatus.VERIFIED) {
        throw new AuthException(AuthErrorCode.USER_ALREADY_EXISTS,
            "an account already exists for " + normalized);
      }
      // Pending registration: adopt the newly submitted password and re-send the OTP.
      user.setPasswordHash(passwordEncoder.encode(password));
      userRepository.save(user);
      issueNewOtp(user, ip);
      return new UserDto(user.getId(), user.getEmail(), user.getStatus());
    }

    User user = new User();
    user.setEmail(normalized);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setStatus(UserStatus.PENDING);
    userRepository.save(user);

    String code = otpCode();
    try {
      otpStore.storeOtpHash(normalized, passwordEncoder.encode(code));
      rateLimiter.resetAttempts(normalized);
      emailService.sendOtp(normalized, code);
    } catch (AuthEmailException e) {
      // Roll back the account so a failed mail never leaves an unverified orphan.
      throw new AuthException(AuthErrorCode.EMAIL_SEND_FAILED,
          "could not send verification email", e);
    }
    return new UserDto(user.getId(), user.getEmail(), user.getStatus());
  }

  /**
   * Verify a 6-digit OTP. After OTP_ATTEMPTS_MAX failed attempts the code is locked
   * until it expires (5-minute TTL) or a new code is requested.
   */
  @Transactional
  public void verifyOtp(String email, String code, String ip) {
    String normalized = normalizeEmail(email);
    User user = userRepository.findByEmail(normalized)
        .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND,
            "no account found for " + normalized));

    if (user.getStatus() == UserStatus.VERIFIED) {
      return; // already verified: idempotent
    }

    if (code == null || code.length() != 6 || !code.chars().allMatch(Character::isDigit)) {
      throw new AuthException(AuthErrorCode.INVALID_OTP, "verification code must be 6 digits");
    }

    Optional<String> stored = otpStore.getOtpHash(normalized);
    if (stored.isEmpty()) {
      throw new AuthException(AuthErrorCode.OTP_EXPIRED,
          "verification code expired or already used; request a new one");
    }

    if (rateLimiter.attempts(normalized) >= OTP_ATTEMPTS_MAX) {
      throw new AuthException(AuthErrorCode.OTP_ATTEMPTS_EXCEEDED,
          "too many verification attempts; request a new code");
    }

    if (!passwordEncoder.matches(code, stored.get())) {
      rateLimiter.recordAttempt(normalized);
      throw new AuthException(AuthErrorCode.INVALID_OTP, "invalid verification code");
    }

    user.setStatus(UserStatus.VERIFIED);
    user.setEmailVerifiedAt(Instant.now());
    userRepository.save(user);
    otpStore.deleteOtpHash(normalized);
    rateLimiter.resetAttempts(normalized);
  }

  /**
   * Re-send a 6-digit OTP. Allowed 3 times per hour per email and per IP; the previous
   * code is invalidated (new hash, new TTL).
   */
  @Transactional
  public void resendOtp(String email, String ip) {
    String normalized = normalizeEmail(email);
    User user = userRepository.findByEmail(normalized)
        .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND,
            "no account found for " + normalized));

    if (user.getStatus() == UserStatus.VERIFIED) {
      throw new AuthException(AuthErrorCode.USER_ALREADY_VERIFIED,
          "account for " + normalized + " is already verified");
    }

    issueNewOtp(user, ip);
  }

  /**
   * Generate, store and email a fresh OTP for a pending user, enforcing the
   * resend rate limit (3/hour per email and per IP). Shared by resendOtp and
   * the re-registration-of-a-pending-account path.
   */
  private void issueNewOtp(User user, String ip) {
    if (!rateLimiter.canResend(user.getEmail(), ip)) {
      throw new AuthException(AuthErrorCode.RESEND_LIMIT_EXCEEDED,
          "too many resends; try again later");
    }

    String code = otpCode();
    try {
      otpStore.storeOtpHash(user.getEmail(), passwordEncoder.encode(code));
      rateLimiter.resetAttempts(user.getEmail());
      rateLimiter.recordResend(user.getEmail(), ip);
      emailService.sendOtp(user.getEmail(), code);
    } catch (AuthEmailException e) {
      throw new AuthException(AuthErrorCode.EMAIL_SEND_FAILED,
          "could not send verification email", e);
    }
  }

  /**
   * Password login for a verified account. Issues a 15-minute access JWT and a rotating
   * refresh token stored (hashed) in Redis. OAuth-only accounts (no password) must use
   * their provider to sign in.
   */
  @Transactional
  public TokenPair login(String email, String password) {
    String normalized = normalizeEmail(email);
    User user = userRepository.findByEmail(normalized)
        .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS,
            "unknown email or wrong password"));

    if (user.getStatus() != UserStatus.VERIFIED) {
      throw new AuthException(AuthErrorCode.ACCOUNT_NOT_VERIFIED,
          "verify your email before logging in");
    }

    if (user.getPasswordHash() == null) {
      throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS,
          "this account uses Google/GitHub sign-in; log in with your provider");
    }

    if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS,
          "unknown email or wrong password");
    }

    return issueTokens(user);
  }

  /**
   * Google/GitHub login from the OAuth2 callback. Auto-links by email: a known provider
   * account signs in directly; an unknown provider account with a known email links to
   * that user (verifying it, since the provider verified the email); an unknown email
   * creates a new VERIFIED user. A provider account with no email and no existing link
   * fails with OAUTH_EMAIL_MISSING (e.g. a fully private GitHub email).
   */
  @Transactional
  public TokenPair loginWithOAuth(String provider, String providerUserId, String email) {
    String normalizedProvider = provider == null ? "" : provider.toLowerCase();
    if (!normalizedProvider.equals("google") && !normalizedProvider.equals("github")) {
      throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_UNSUPPORTED,
          "unsupported OAuth provider: " + provider);
    }
    if (providerUserId == null || providerUserId.isBlank()) {
      throw new AuthException(AuthErrorCode.OAUTH_FAILED, "OAuth provider returned no user id");
    }

    Optional<OAuthAccount> linked =
        oauthAccountRepository.findByProviderAndProviderUserId(normalizedProvider, providerUserId);
    if (linked.isPresent()) {
      return issueTokens(linked.get().getUser());
    }

    String normalizedEmail = normalizeEmail(email);
    if (normalizedEmail == null || !EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
      throw new AuthException(AuthErrorCode.OAUTH_EMAIL_MISSING,
          "the " + normalizedProvider + " account has no visible email; register with email first "
              + "or make the provider email visible, then retry");
    }

    User user = userRepository.findByEmail(normalizedEmail).orElseGet(() -> {
      User created = new User();
      created.setEmail(normalizedEmail);
      created.setPasswordHash(null); // OAuth-only account: no password login
      created.setStatus(UserStatus.VERIFIED);
      created.setEmailVerifiedAt(Instant.now());
      return userRepository.save(created);
    });

    if (user.getStatus() != UserStatus.VERIFIED) {
      user.setStatus(UserStatus.VERIFIED);
      user.setEmailVerifiedAt(Instant.now());
    }

    OAuthAccount account = new OAuthAccount();
    account.setUser(user);
    account.setProvider(normalizedProvider);
    account.setProviderUserId(providerUserId);
    account.setEmail(normalizedEmail);
    oauthAccountRepository.save(account);

    return issueTokens(user);
  }

  /** Load a user by id (for GET /api/auth/me). */
  @Transactional(readOnly = true)
  public UserDto getUser(String userId) {
    User user = userRepository.findById(UUID.fromString(userId))
        .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND, "user not found"));
    return new UserDto(user.getId(), user.getEmail(), user.getStatus());
  }

  /** Load a user by email (for the verify-otp response). */
  @Transactional(readOnly = true)
  public UserDto getUserByEmail(String email) {
    String normalized = normalizeEmail(email);
    User user = userRepository.findByEmail(normalized)
        .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND,
            "no account found for " + normalized));
    return new UserDto(user.getId(), user.getEmail(), user.getStatus());
  }

  /**
   * Rotate the refresh token: the presented token is consumed (revoked) and a new pair is
   * issued. Re-using a consumed token fails with INVALID_REFRESH_TOKEN.
   */
  @Transactional
  public TokenPair refresh(String refreshToken) {
    String userId = refreshTokenStore.consume(refreshToken)
        .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN,
            "unknown or revoked refresh token"));

    User user = userRepository.findById(UUID.fromString(userId))
        .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN,
            "refresh token user not found"));

    if (user.getStatus() != UserStatus.VERIFIED) {
      throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN, "account not verified");
    }

    return issueTokens(user);
  }

  /** Revoke the presented refresh token (logout). Idempotent. */
  @Transactional
  public void logout(String refreshToken) {
    refreshTokenStore.revoke(refreshToken);
  }

  private TokenPair issueTokens(User user) {
    user.setLastLoginAt(Instant.now());
    userRepository.save(user);
    String accessToken = jwtService.createAccessToken(user.getId().toString(), user.getEmail());
    String refreshToken = refreshTokenStore.create(user.getId().toString());
    return new TokenPair(accessToken, refreshToken, ACCESS_TTL_SECONDS);
  }

  private static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase();
  }

  private static String otpCode() {
    return String.format("%06d", (int) (java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)));
  }
}
