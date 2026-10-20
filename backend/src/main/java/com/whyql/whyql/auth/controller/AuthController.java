package com.whyql.whyql.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.whyql.whyql.auth.config.OAuthProperties;
import com.whyql.whyql.auth.dto.AuthDtos.AckResponse;
import com.whyql.whyql.auth.dto.AuthDtos.LoginRequest;
import com.whyql.whyql.auth.dto.AuthDtos.LogoutRequest;
import com.whyql.whyql.auth.dto.AuthDtos.RefreshRequest;
import com.whyql.whyql.auth.dto.AuthDtos.RegisterRequest;
import com.whyql.whyql.auth.dto.AuthDtos.ResendOtpRequest;
import com.whyql.whyql.auth.dto.AuthDtos.SetPasswordRequest;
import com.whyql.whyql.auth.dto.AuthDtos.TokenResponse;
import com.whyql.whyql.auth.dto.AuthDtos.UserResponse;
import com.whyql.whyql.auth.dto.AuthDtos.VerifyOtpRequest;
import com.whyql.whyql.auth.security.OAuthReturnCookie;
import com.whyql.whyql.auth.service.AuthErrorCode;
import com.whyql.whyql.auth.service.AuthException;
import com.whyql.whyql.auth.service.AuthService;

/**
 * REST entry point for auth. Browser SPA sign-in starts at GET /api/auth/oauth/{provider}
 * (return URL in the oauth_return cookie; see OAuthReturnCookie) and direct API
 * clients may open Spring's GET /oauth2/authorization/{google|github} themselves;
 * both finish at the OAuth2 callback, which delivers our own token pair as JSON
 * or as a 303 fragment redirect (see OAuth2LoginSuccessHandler).
 * The client IP comes from the servlet request for OTP rate limiting.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;
  private final OAuthProperties oauthProperties;

  private static final Set<String> OAUTH_PROVIDERS = Set.of("google", "github");

  public AuthController(AuthService authService, OAuthProperties oauthProperties) {
    this.authService = authService;
    this.oauthProperties = oauthProperties;
  }

  @PostMapping("/register")
  public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request,
      HttpServletRequest httpRequest) {
    AuthService.UserDto user = authService.register(request.email(), request.password(),
        httpRequest.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new UserResponse(user.id(), user.email(), user.status().name()));
  }

  @PostMapping("/verify-otp")
  public UserResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request,
      HttpServletRequest httpRequest) {
    authService.verifyOtp(request.email(), request.code(), httpRequest.getRemoteAddr());
    AuthService.UserDto user = authService.getUserByEmail(request.email());
    return new UserResponse(user.id(), user.email(), user.status().name());
  }

  @PostMapping("/resend-otp")
  public AckResponse resendOtp(@Valid @RequestBody ResendOtpRequest request,
      HttpServletRequest httpRequest) {
    authService.resendOtp(request.email(), httpRequest.getRemoteAddr());
    return new AckResponse(true);
  }

  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest request) {
    AuthService.TokenPair tokens = authService.login(request.email(), request.password());
    return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.expiresInSeconds());
  }

  @PostMapping("/refresh")
  public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
    AuthService.TokenPair tokens = authService.refresh(request.refreshToken());
    return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.expiresInSeconds());
  }

  @PostMapping("/logout")
  public AckResponse logout(@Valid @RequestBody LogoutRequest request) {
    authService.logout(request.refreshToken());
    return new AckResponse(true);
  }

  /** Current user from the Bearer JWT (principal = user id, set by JwtAuthenticationFilter). */
  @GetMapping("/me")
  public UserResponse me(Authentication authentication) {
    AuthService.UserDto user = authService.getUser(authentication.getName());
    return new UserResponse(user.id(), user.email(), user.status().name());
  }

  /**
   * Step 1 of "adopt a local password" for OAuth-only accounts: email the
   * signed-in user a 6-digit code. Requires a Bearer JWT and an account
   * without a local password; rate limited 3/hour like OTP resends.
   */
  @PostMapping("/password/set/init")
  public AckResponse sendSetPasswordOtp(HttpServletRequest httpRequest,
      Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN, "sign in first");
    }
    authService.sendSetPasswordOtp(authentication.getName(), httpRequest.getRemoteAddr());
    return new AckResponse(true);
  }

  /** Step 2: verify the emailed code and set the local password hash. */
  @PostMapping("/password/set")
  public AckResponse setPassword(
      @Valid @RequestBody SetPasswordRequest request, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN, "sign in first");
    }
    authService.setPassword(authentication.getName(), request.code(), request.password());
    return new AckResponse(true);
  }

  /**
   * Browser entry point for SPA-driven OAuth: validates the return URL against the
   * allowlist, stores it in the short-lived oauth_return cookie, and hands off to
   * Spring's provider authorization endpoint. The callback then 303-redirects the
   * browser back to returnTo with the token pair in the URL fragment. Without
   * returnTo this is a plain 302 into the dance (JSON delivery at the callback).
   */
  @GetMapping("/oauth/{provider}")
  public ResponseEntity<Void> startOAuth(@PathVariable String provider,
      @RequestParam(required = false) String returnTo, HttpServletResponse response) {
    String normalized = provider == null ? "" : provider.toLowerCase();
    if (!OAUTH_PROVIDERS.contains(normalized)) {
      throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_UNSUPPORTED,
          "unsupported OAuth provider: " + provider);
    }

    ResponseEntity<Void> redirect = ResponseEntity.status(HttpStatus.FOUND)
        .location(java.net.URI.create("/oauth2/authorization/" + normalized))
        .build();

    if (returnTo == null) {
      return redirect;
    }
    if (!OAuthReturnCookie.allowed(returnTo, oauthProperties.returnToAllowlist())) {
      throw new AuthException(AuthErrorCode.OAUTH_RETURN_INVALID,
          "returnTo is not on the allowlist");
    }

    response.addCookie(OAuthReturnCookie.issue(returnTo));
    return redirect;
  }
}
