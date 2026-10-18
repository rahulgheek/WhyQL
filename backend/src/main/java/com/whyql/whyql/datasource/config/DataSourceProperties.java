package com.whyql.whyql.datasource.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datasource safety switches.
 * whyql.datasource.cloud-mode (default true): SSRF guard blocks internal hosts.
 * whyql.datasource.allowlist: hosts that bypass the guard (local development).
 */
@ConfigurationProperties(prefix = "whyql.datasource")
public record DataSourceProperties(boolean cloudMode, List<String> allowlist) {

  public DataSourceProperties {
    if (allowlist == null) {
      allowlist = List.of();
    }
  }
}
