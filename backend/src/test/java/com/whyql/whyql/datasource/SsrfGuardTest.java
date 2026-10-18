package com.whyql.whyql.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.config.DataSourceProperties;
import com.whyql.whyql.datasource.DataSourceErrorCode;
import com.whyql.whyql.datasource.DataSourceException;
import com.whyql.whyql.datasource.service.SsrfGuard;

/**
 * Plain unit test for the SSRF guard (no network, no Spring): cloud mode blocks
 * internal hosts, local mode and the allowlist bypass, errors never leak secrets.
 */
class SsrfGuardTest {

  private static SsrfGuard cloudGuard() {
    return new SsrfGuard(new DataSourceProperties(true, List.of()));
  }

  private static void assertCode(Throwable t, DataSourceErrorCode expected) {
    assertThat(t).isInstanceOf(DataSourceException.class);
    assertThat(((DataSourceException) t).code()).isEqualTo(expected);
  }

  @Test
  void cloudModeBlocksInternalHosts() {
    assertThatThrownBy(() -> cloudGuard().check("jdbc:postgresql://127.0.0.1:5432/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.BLOCKED_HOST));
    assertThatThrownBy(() -> cloudGuard().check("jdbc:mysql://10.1.2.3:3306/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.BLOCKED_HOST));
    assertThatThrownBy(() -> cloudGuard().check("jdbc:postgresql://192.168.1.10/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.BLOCKED_HOST));
    assertThatThrownBy(() -> cloudGuard().check("jdbc:postgresql://172.16.0.5/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.BLOCKED_HOST));
    // Cloud metadata endpoint lives on link-local.
    assertThatThrownBy(() -> cloudGuard().check("jdbc:postgresql://169.254.169.254/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.BLOCKED_HOST));
    assertThatThrownBy(() -> cloudGuard().check("jdbc:postgresql://localhost/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.BLOCKED_HOST));
  }

  @Test
  void errorMessagesNeverContainCredentials() {
    String password = "sup3r-s3cret-pw";
    assertThatThrownBy(() -> cloudGuard()
        .check("jdbc:postgresql://admin:" + password + "@127.0.0.1:5432/db"))
        .satisfies(t -> {
          assertCode(t, DataSourceErrorCode.BLOCKED_HOST);
          assertThat(t.getMessage()).doesNotContain(password).doesNotContain("admin:");
        });
  }

  @Test
  void localModeAllowsInternalHosts() {
    SsrfGuard local = new SsrfGuard(new DataSourceProperties(false, List.of()));
    assertThat(local.check("jdbc:postgresql://127.0.0.1:5432/db")).isEqualTo("127.0.0.1");
    assertThat(local.check("jdbc:mysql://localhost:3306/db")).isEqualTo("localhost");
  }

  @Test
  void allowlistBypassesCloudMode() {
    SsrfGuard guard =
        new SsrfGuard(new DataSourceProperties(true, List.of("localhost", "db.internal")));
    assertThat(guard.check("jdbc:postgresql://localhost:5432/db")).isEqualTo("localhost");
  }

  @Test
  void nonJdbcAndUnresolvableHostsRejected() {
    assertThatThrownBy(() -> cloudGuard().check("http://example.com/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.INVALID_URL));
    assertThatThrownBy(() -> cloudGuard().check("jdbc:oracle://example.com/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.INVALID_URL));
    assertThatThrownBy(() -> cloudGuard().check(null))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.INVALID_URL));
    assertThatThrownBy(() -> cloudGuard().check("jdbc:postgresql://no-such-host-xyz.invalid/db"))
        .satisfies(t -> assertCode(t, DataSourceErrorCode.UNREACHABLE_HOST));
  }

  @Test
  void privilegedUsernamesDetected() {
    assertThat(SsrfGuard.looksPrivileged("postgres")).isTrue();
    assertThat(SsrfGuard.looksPrivileged("Root")).isTrue();
    assertThat(SsrfGuard.looksPrivileged("app_reader")).isFalse();
    assertThat(SsrfGuard.looksPrivileged(null)).isFalse();
  }
}
