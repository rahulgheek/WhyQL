package com.whyql.whyql.auth.security;

import java.time.Duration;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Shared plumbing for the SPA-facing OAuth round trip. The frontend starts the
 * dance at GET /api/auth/oauth/{provider}?returnTo=... which stores the validated
 * return URL in the short-lived "oauth_return" cookie; the success/failure
 * handlers erase the cookie and 303-redirect there with the token pair (or the
 * error code) in the URL fragment, which browsers never resend to any server.
 */
public final class OAuthReturnCookie {

  public static final String NAME = "oauth_return";
  static final Duration MAX_AGE = Duration.ofMinutes(5);

  private OAuthReturnCookie() {
  }

  /** The first oauth_return cookie's raw value, or null when absent/without a value. */
  public static String raw(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (NAME.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  /** Cookie for the validated return URL; only called after the caller checked the allowlist. */
  public static Cookie issue(String returnTo) {
    Cookie cookie = new Cookie(NAME, returnTo);
    cookie.setHttpOnly(true);
    cookie.setPath("/");
    cookie.setAttribute("SameSite", "Lax");
    cookie.setMaxAge((int) MAX_AGE.toSeconds());
    return cookie;
  }

  /** Erase the cookie so a stale value cannot redirect a later provider login. */
  public static void clear(HttpServletResponse response) {
    Cookie cookie = new Cookie(NAME, "");
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    cookie.setMaxAge(0);
    response.addCookie(cookie);
  }

  /** Exact allowlist membership; used by both the controller entry and the handlers. */
  public static boolean allowed(String returnTo, Set<String> allowlist) {
    return returnTo != null && allowlist.contains(returnTo);
  }
}
