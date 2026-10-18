package com.whyql.whyql.auth.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SPA-facing OAuth return wiring.
 * whyql.auth.oauth.return-to-allowlist: the exact URLs the browser may be redirected
 * back to after the provider callback. The token pair travels in the URL fragment
 * (#accessToken=...), which browsers never send to servers. Empty set = browser
 * redirects disabled (plain JSON responses for the API-first clients).
 */
@ConfigurationProperties(prefix = "whyql.auth.oauth")
public record OAuthProperties(Set<String> returnToAllowlist) {

  public OAuthProperties {
    returnToAllowlist = returnToAllowlist == null ? Set.of() : Set.copyOf(returnToAllowlist);
  }
}
