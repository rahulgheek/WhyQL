package com.whyql.whyql.datasource.service;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.DataSourceErrorCode;
import com.whyql.whyql.datasource.DataSourceException;
import com.whyql.whyql.datasource.config.DataSourceProperties;

/**
 * SSRF guard for user-supplied JDBC URLs. In cloud mode (default) any host that
 * resolves to loopback, site-local (10/172.16/192.168), link-local (covers the
 * 169.254.169.254 cloud-metadata address), or multicast is rejected. The explicit
 * allowlist bypasses checks for local development (e.g. localhost when the app
 * runs locally); with cloud mode off everything is allowed.
 *
 * <p>Error messages carry only the host, never the URL: connection strings may
 * embed passwords and must never reach logs or API responses.
 */
@Component
public class SsrfGuard {

  private static final Pattern HOST_PATTERN =
      Pattern.compile("^jdbc:(?:postgresql|mysql)://(?:[^@/]*@)?([^/:?;]+)");

  private final DataSourceProperties properties;

  public SsrfGuard(DataSourceProperties properties) {
    this.properties = properties;
  }

  /** Validates the URL and returns the extracted host, or throws. */
  public String check(String jdbcUrl) {
    if (jdbcUrl == null) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_URL, "JDBC URL is required");
    }
    String url = jdbcUrl.trim();
    Matcher matcher = HOST_PATTERN.matcher(url);
    if (!matcher.find()) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_URL,
          "only jdbc:postgresql:// and jdbc:mysql:// URLs are supported");
    }
    String host = matcher.group(1).toLowerCase(Locale.ROOT);

    if (properties.allowlist().contains(host)) {
      return host;
    }
    if (!properties.cloudMode()) {
      return host;
    }

    final InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.UNREACHABLE_HOST,
          "cannot resolve database host: " + host);
    }
    for (InetAddress address : addresses) {
      if (address.isLoopbackAddress() || address.isSiteLocalAddress()
          || address.isLinkLocalAddress() || address.isMulticastAddress()) {
        throw new DataSourceException(DataSourceErrorCode.BLOCKED_HOST,
            "database host resolves to a blocked (internal) address: " + host
                + ". Run the app locally with cloud mode off or add the host to the allowlist.");
      }
    }
    return host;
  }

  /** Usernames that smell like superusers; callers warn to prefer a read-only account. */
  public static boolean looksPrivileged(String username) {
    if (username == null) {
      return false;
    }
    return List.of("postgres", "root", "admin", "administrator", "sa", "sys", "system")
        .contains(username.trim().toLowerCase(Locale.ROOT));
  }
}
