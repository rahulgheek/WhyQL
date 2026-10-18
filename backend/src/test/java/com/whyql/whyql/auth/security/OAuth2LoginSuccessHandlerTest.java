package com.whyql.whyql.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.whyql.whyql.auth.config.OAuthProperties;
import com.whyql.whyql.auth.service.AuthErrorCode;
import com.whyql.whyql.auth.service.AuthException;
import com.whyql.whyql.auth.service.AuthService;

/**
 * Delivery modes of the OAuth callback: JSON for API clients and a 303 fragment
 * redirect to the allowlisted SPA callback for browser clients, plus the same
 * split on the AuthException path.
 */
class OAuth2LoginSuccessHandlerTest {

  private static final String COOKIE = OAuthReturnCookie.NAME;
  private static final String RETURN_TO = "http://localhost:3000/auth/callback";

  private final AuthService authService = mock(AuthService.class);
  private final OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
  private final OAuth2LoginSuccessHandler handler = new OAuth2LoginSuccessHandler(authService,
      authorizedClients, new OAuthProperties(Set.of(RETURN_TO)));

  private OAuth2AuthenticationToken githubLogin() {
    OAuth2User principal = new DefaultOAuth2User(
        List.of(new SimpleGrantedAuthority("ROLE_USER")),
        Map.of("sub", "gh-user-1", "email", "grace@example.com"), "sub");
    return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");
  }

  private MockHttpServletRequest requestWithCookie(String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (value != null) {
      request.setCookies(new jakarta.servlet.http.Cookie(COOKIE, value));
    }
    return request;
  }

  @Test
  void spaClientGets303RedirectWithTokenFragmentAndClearedCookie() throws Exception {
    when(authService.loginWithOAuth(eq("github"), eq("gh-user-1"), any()))
        .thenReturn(new AuthService.TokenPair("access.jwt", "refresh-token", 900));

    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(requestWithCookie(RETURN_TO), response, githubLogin());

    assertThat(response.getStatus()).isEqualTo(303);
    assertThat(response.getHeader("Location"))
        .isEqualTo(RETURN_TO + "#accessToken=access.jwt&refreshToken=refresh-token&expiresInSeconds=900");
    assertThat(response.getHeaderValues("Location")).hasSize(1);
    assertThat(String.join(",", response.getHeaders("Set-Cookie"))).contains(COOKIE + "=;", "Max-Age=0");
  }

  @Test
  void apiClientGetsJsonTokenPairWithoutCookie() throws Exception {
    when(authService.loginWithOAuth(eq("github"), eq("gh-user-1"), any()))
        .thenReturn(new AuthService.TokenPair("access.jwt", "refresh-token", 900));

    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(requestWithCookie(null), response, githubLogin());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString())
        .contains("\"accessToken\":\"access.jwt\"", "\"refreshToken\":\"refresh-token\"", "900");
  }

  @Test
  void spaClientOnAuthFailureRedirectsWithErrorFragment() throws Exception {
    when(authService.loginWithOAuth(eq("github"), eq("gh-user-1"), any()))
        .thenThrow(new AuthException(AuthErrorCode.OAUTH_EMAIL_MISSING, "no visible email"));

    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(requestWithCookie(RETURN_TO), response, githubLogin());

    assertThat(response.getStatus()).isEqualTo(303);
    assertThat(response.getHeader("Location")).isEqualTo(
        RETURN_TO + "#error=OAUTH_EMAIL_MISSING&errorDescription=no+visible+email");
  }

  @Test
  void apiClientOnAuthFailureGetsJsonError() throws Exception {
    when(authService.loginWithOAuth(eq("github"), eq("gh-user-1"), any()))
        .thenThrow(new AuthException(AuthErrorCode.OAUTH_EMAIL_MISSING, "no visible email"));

    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(requestWithCookie(null), response, githubLogin());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("\"code\":\"OAUTH_EMAIL_MISSING\"");
  }

  @Test
  void tamperedCookieIsRejectedBeforeTheLoginHappens() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(
        requestWithCookie("http://evil.example.com/auth/callback"), response, githubLogin());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("\"code\":\"OAUTH_RETURN_INVALID\"");
    verify(authService, never()).loginWithOAuth(any(), any(), any());
  }
}
