package com.whyql.whyql.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import com.whyql.whyql.auth.config.OAuthProperties;

/**
 * The OAuth failure partner of OAuth2LoginSuccessHandlerTest: SPA clients get the
 * provider error as a 303 fragment redirect, API clients as 401 JSON.
 */
class OAuth2LoginFailureHandlerTest {

  private static final String RETURN_TO = "http://localhost:3000/auth/callback";
  private final OAuth2LoginFailureHandler handler =
      new OAuth2LoginFailureHandler(new OAuthProperties(Set.of(RETURN_TO)));

  private MockHttpServletRequest requestWithCookie(String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (value != null) {
      request.setCookies(new jakarta.servlet.http.Cookie(OAuthReturnCookie.NAME, value));
    }
    return request;
  }

  @Test
  void spaClientGets303WithProviderErrorFragment() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationFailure(requestWithCookie(RETURN_TO), response,
        new OAuth2AuthenticationException(new OAuth2Error("invalid_grant", "state mismatch", null)));

    assertThat(response.getStatus()).isEqualTo(303);
    assertThat(response.getHeader("Location")).isEqualTo(
        RETURN_TO + "#error=invalid_grant&errorDescription=state+mismatch");
    assertThat(String.join(",", response.getHeaders("Set-Cookie"))).contains("Max-Age=0");
  }

  @Test
  void apiClientGets401Json() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationFailure(requestWithCookie(null), response,
        new OAuth2AuthenticationException(new OAuth2Error("invalid_grant", "state mismatch", null)));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString())
        .contains("\"code\":\"invalid_grant\"", "\"message\":\"state mismatch\"");
  }
}
