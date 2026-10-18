package com.whyql.whyql.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.whyql.whyql.auth.config.OAuthProperties;
import tools.jackson.databind.ObjectMapper;

/**
 * Mirrors {@link OAuth2LoginSuccessHandler} for failed provider round trips:
 * SPA clients (valid oauth_return cookie) get a 303 with the provider error in
 * the URL fragment; plain API clients get the {"code","message"} JSON as 401.
 */
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final OAuthProperties oauthProperties;

  public OAuth2LoginFailureHandler(OAuthProperties oauthProperties) {
    this.oauthProperties = oauthProperties;
  }

  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException, ServletException {
    String code = "OAUTH_FAILED";
    String message = "OAuth login failed";
    if (exception instanceof OAuth2AuthenticationException oauthException) {
      OAuth2Error error = oauthException.getError();
      code = error.getErrorCode();
      message = error.getDescription() == null ? code : error.getDescription();
    }

    String returnTo = OAuthReturnCookie.raw(request);
    if (returnTo != null && OAuthReturnCookie.allowed(returnTo, oauthProperties.returnToAllowlist())) {
      OAuthReturnCookie.clear(response);
      response.setStatus(HttpStatus.SEE_OTHER.value());
      response.setHeader("Location", returnTo
          + "#error=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
          + "&errorDescription=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
      return;
    }

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType("application/json");
    OBJECT_MAPPER.writeValue(response.getWriter(),
        java.util.Map.of("code", code, "message", message));
  }
}
