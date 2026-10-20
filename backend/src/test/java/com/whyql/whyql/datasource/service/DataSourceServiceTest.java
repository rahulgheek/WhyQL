package com.whyql.whyql.datasource.service;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.whyql.whyql.datasource.adapter.DataSourceAdapter;
import com.whyql.whyql.datasource.domain.DataSource;
import com.whyql.whyql.datasource.repository.DataSourceRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Credential-manager unit tests: reveal is denied without a matching account
 * password (and rate limited), and updateCredentials live-tests the merged
 * connection before persisting. Sandbox sources reject both operations.
 */
class DataSourceServiceTest {

  private DataSourceRepository repository;
  private CredentialCrypto crypto;
  private SsrfGuard ssrfGuard;
  private DataSourceAdapter adapter;
  private javax.sql.DataSource appDataSource;
  private AccountPasswordValidator passwordValidator;
  private RevealRateLimiter rateLimiter;
  private DataSourceService service;

  private DataSource source;
  private static final String OWNER = "user-1";

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(DataSourceRepository.class);
    crypto = Mockito.mock(CredentialCrypto.class);
    ssrfGuard = Mockito.mock(SsrfGuard.class);
    adapter = Mockito.mock(DataSourceAdapter.class);
    appDataSource = Mockito.mock(javax.sql.DataSource.class);
    passwordValidator = Mockito.mock(AccountPasswordValidator.class);
    rateLimiter = Mockito.mock(RevealRateLimiter.class);
    service = new DataSourceService(repository, crypto, ssrfGuard, adapter,
        appDataSource, Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
        passwordValidator, rateLimiter);

    source = new DataSource();
    source.setId(UUID.randomUUID());
    source.setOwnerUserId(OWNER);
    source.setEngine(com.whyql.whyql.datasource.domain.Engine.POSTGRES);
    source.setJdbcUrlEnc("enc-url");
    source.setUsernameEnc("enc-user");
    source.setPasswordEnc("enc-pass");

    lenient().when(repository.findByIdAndOwnerUserId(source.getId(), OWNER))
        .thenReturn(java.util.Optional.of(source));
    lenient().when(crypto.decrypt(any())).thenReturn("plain");
    lenient().when(rateLimiter.allowed(OWNER)).thenReturn(true);
    lenient().when(passwordValidator.matches(OWNER, "right")).thenReturn(true);
    lenient().when(passwordValidator.hasPassword(OWNER)).thenReturn(true);
  }

  private static final String OK_PW = "right";

  @Test
  void revealReturnsPlaintextAfterPasswordGate() {
    DataSourceService.RevealedCredentials revealed =
        service.revealedCredentials(source.getId(), OWNER, "right");

    assertEquals("plain", revealed.password());
    assertEquals("plain", revealed.jdbcUrl());
    verify(rateLimiter).allowed(OWNER);
  }

  @Test
  void revealRejectsWrongAccountPassword() {
    assertThrows(DataSourceException.class,
        () -> service.revealedCredentials(source.getId(), OWNER, "wrong"));
  }

  @Test
  void revealRejectsWhenRateLimited() {
    when(rateLimiter.allowed(OWNER)).thenReturn(false);
    assertThrows(DataSourceException.class,
        () -> service.revealedCredentials(source.getId(), OWNER, "right"));
  }

  @Test
  void revealExplainsOAuthOnlyAccounts() {
    when(passwordValidator.hasPassword(OWNER)).thenReturn(false);
    DataSourceException e = assertThrows(DataSourceException.class,
        () -> service.revealedCredentials(source.getId(), OWNER, "right"));
    assertEquals(DataSourceErrorCode.NO_ACCOUNT_PASSWORD, e.code());
  }

  @Test
  void sandboxSourcesRejectCredentialOps() {
    source.setSandboxSchema("sandbox_x");
    assertThrows(DataSourceException.class,
        () -> service.revealedCredentials(source.getId(), OWNER, "right"));
    assertThrows(DataSourceException.class,
        () -> service.updateCredentials(source.getId(), OWNER, null, null, "newpass", OK_PW));
  }

  @Test
  void updateCredentialsMergesAndPersistsOnlyChangedFields() {
    var saved = new java.util.concurrent.atomic.AtomicReference<DataSource>();
    when(repository.save(any(DataSource.class))).thenAnswer(inv -> {
      saved.set(inv.getArgument(0));
      return inv.getArgument(0);
    });

    service.updateCredentials(source.getId(), OWNER, null, "newuser", "newpass", OK_PW);

    // URL unchanged → decrypt of empty string happens though; adapter gets multipart args
    try {
      verify(adapter).testConnection(any());
    } catch (Exception ignored) {
      // direct mock call in verify() still requires declared-exception handling
    }
    verify(crypto).encrypt("newuser");
    verify(crypto).encrypt("newpass");
  }

  @Test
  void updateCredentialsRejectsWrongAccountPassword() {
    assertThrows(DataSourceException.class, () -> service.updateCredentials(
        source.getId(), OWNER, null, null, "newpass", "bad"));

    verify(repository, never()).save(any(DataSource.class));
  }

  @Test
  void updateCredentialsDoesNotPersistFailedConnections() {
    try {
      Mockito.doThrow(new DataSourceAdapter.AdapterException("code", "bad"))
          .when(adapter).testConnection(any());
    } catch (Exception ignored) {
      // declared-exception handling for the direct mock call
    }

    assertThrows(DataSourceException.class, () -> service.updateCredentials(
        source.getId(), OWNER, null, null, "typo", OK_PW));

    verify(repository, never()).save(any(DataSource.class));
  }
}
