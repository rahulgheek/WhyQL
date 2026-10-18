package com.whyql.whyql.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.whyql.whyql.auth.config.OAuthProperties;
import com.whyql.whyql.auth.controller.AuthExceptionHandler;
import com.whyql.whyql.auth.service.AuthErrorCode;
import com.whyql.whyql.auth.service.AuthException;
import com.whyql.whyql.auth.service.AuthService;
import tools.jackson.databind.ObjectMapper;

/**
 * Finishes the backend OAuth2 login (Google/GitHub): exchanges the provider user for
 * our own 15-minute JWT + rotating refresh token. Delivery modes, decided up front
 * from the "oauth_return" cookie (see {@link OAuthReturnCookie}):
 * <ul>
 * <li>API clients (no cookie): the token pair comes back as JSON.</li>
 * <li>SPA clients (allowlisted cookie): a 303 redirect back to the frontend callback
 * URL with the token pair in the URL fragment (#accessToken=...), which browsers
 * never send to servers.</li>
 * <li>Tampered cookie value: 400 OAUTH_RETURN_INVALID JSON, login skipped.</li>
 * </ul>
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

  private final AuthService authService;
  private final OAuth2AuthorizedClientService authorizedClientService;
  private final OAuthProperties oauthProperties;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public OAuth2LoginSuccessHandler(AuthService authService,
      OAuth2AuthorizedClientService authorizedClientService, OAuthProperties oauthProperties) {
    this.authService = authService;
    this.authorizedClientService = authorizedClientService;
    this.oauthProperties = oauthProperties;
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException, ServletException {
    String rawReturnTo = OAuthReturnCookie.raw(request);
    if (rawReturnTo != null
        && !OAuthReturnCookie.allowed(rawReturnTo, oauthProperties.returnToAllowlist())) {
      OAuthReturnCookie.clear(response);
      writeJson(response, AuthExceptionHandler.statusFor(AuthErrorCode.OAUTH_RETURN_INVALID),
          Map.of("code", AuthErrorCode.OAUTH_RETURN_INVALID.name(),
              "message", "oauth_return cookie is not on the allowlist"));
      return;
    }

    OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
    String provider = oauthToken.getAuthorizedClientRegistrationId();
    OAuth2User principal = oauthToken.getPrincipal();
    String providerUserId = principal.getName();
    String email = principal.getAttribute("email");

    // GitHub hides the email when the user keeps it private; fall back to the
    // /user/emails endpoint (user:email scope is requested in application.yaml).
    if (email == null && "github".equalsIgnoreCase(provider)) {
      email = fetchPrimaryVerifiedGithubEmail(oauthToken);
    }

    try {
      AuthService.TokenPair tokens = authService.loginWithOAuth(provider, providerUserId, email);
      if (rawReturnTo != null) {
        OAuthReturnCookie.clear(response);
        Map<String, String> fragment = new LinkedHashMap<>();
        fragment.put("accessToken", tokens.accessToken());
        fragment.put("refreshToken", tokens.refreshToken());
        fragment.put("expiresInSeconds", String.valueOf(tokens.expiresInSeconds()));
        // 303 (see other): the browser drops back to GET before hitting the SPA page.
        response.setStatus(HttpStatus.SEE_OTHER.value());
        response.setHeader("Location", rawReturnTo + "#" + encoded(fragment));
        return;
      }
      writeJson(response, HttpStatus.OK, Map.of(
          "accessToken", tokens.accessToken(),
          "refreshToken", tokens.refreshToken(),
          "expiresInSeconds", tokens.expiresInSeconds()));
    } catch (AuthException e) {
      deliverError(response, rawReturnTo, e);
    }
  }

  private void deliverError(HttpServletResponse response, String returnTo, AuthException e)
      throws IOException {
    if (returnTo != null) {
      OAuthReturnCookie.clear(response);
      response.setStatus(HttpStatus.SEE_OTHER.value());
      response.setHeader("Location", returnTo + "#error=" + enc(e.code().name())
          + "&errorDescription=" + enc(e.getMessage()));
      return;
    }
    writeJson(response, AuthExceptionHandler.statusFor(e.code()),
        Map.of("code", e.code().name(), "message", e.getMessage()));
  }

  private String fetchPrimaryVerifiedGithubEmail(OAuth2AuthenticationToken oauthToken) {
    OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
        oauthToken.getAuthorizedClientRegistrationId(), oauthToken.getName());
    if (client == null) {
      return null;
    }
    try {
      GithubEmail[] emails = RestClient.create().get()
          .uri("https://api.github.com/user/emails")
          .header("Authorization", "Bearer " + client.getAccessToken().getTokenValue())
          .header("Accept", "application/vnd.github+json")
          .retrieve()
          .body(GithubEmail[].class);
      if (emails == null) {
        return null;
      }
      for (GithubEmail entry : emails) {
        if (entry.primary() && entry.verified()) {
          return entry.email();
        }
      }
      return null;
    } catch (Exception e) {
      // No network/permission edge case should break the callback; the service
      // reports OAUTH_EMAIL_MISSING with guidance instead.
      return null;
    }
  }

  private String encoded(Map<String, String> fragment) {
    StringBuilder builder = new StringBuilder();
    fragment.forEach((key, value) -> {
      if (builder.length() > 0) {
        builder.append('&');
      }
      builder.append(enc(key)).append('=').append(enc(value));
    });
    return builder.toString();
  }

  private String enc(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private void writeJson(HttpServletResponse response, HttpStatus status, Map<String, ?> body)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType("application/json");
    objectMapper.writeValue(response.getWriter(), body);
  }

  private record GithubEmail(String email, boolean primary, boolean verified) {}
}
