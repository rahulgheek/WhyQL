package com.whyql.whyql.datasource.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whyql.whyql.datasource.DataSourceErrorCode;
import com.whyql.whyql.datasource.DataSourceException;
import com.whyql.whyql.datasource.DataSourceAdapter;
import com.whyql.whyql.datasource.DataSourceDeleted;
import com.whyql.whyql.datasource.DataSourceView;
import com.whyql.whyql.datasource.Engine;
import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.DataSource;
import com.whyql.whyql.datasource.repository.DataSourceRepository;

/**
 * URL data sources: SSRF check → live connection test → AES-GCM persistence.
 * Views handed out never contain secrets; plaintext credentials only exist in
 * memory inside this module.
 */
@Service
public class DataSourceService {

  private final DataSourceRepository repository;
  private final CredentialCrypto crypto;
  private final SsrfGuard ssrfGuard;
  private final DataSourceAdapter adapter;
  private final javax.sql.DataSource appDataSource;
  private final ApplicationEventPublisher events;

  public DataSourceService(DataSourceRepository repository, CredentialCrypto crypto,
      SsrfGuard ssrfGuard, DataSourceAdapter adapter, javax.sql.DataSource appDataSource,
      ApplicationEventPublisher events) {
    this.repository = repository;
    this.crypto = crypto;
    this.ssrfGuard = ssrfGuard;
    this.adapter = adapter;
    this.appDataSource = appDataSource;
    this.events = events;
  }

  public record AddResult(DataSourceView view, List<String> warnings) {}

  /** Add a URL data source after testing the live connection. */
  @Transactional
  public AddResult addByUrl(String ownerUserId, String name, Engine engine, String jdbcUrl,
      String username, String password) {
    if (name == null || name.isBlank()) {
      throw new DataSourceException(
          DataSourceErrorCode.INVALID_URL,
          "data source name is required");
    }
    if (engine == null || !adapter.supports(engine)) {
      throw new DataSourceException(
          DataSourceErrorCode.ENGINE_MISMATCH,
          "unsupported engine for JDBC sources: " + engine);
    }
    ssrfGuard.check(jdbcUrl);
    testLiveConnection(engine, jdbcUrl, username, password);

    List<String> warnings = new ArrayList<>();
    if (SsrfGuard.looksPrivileged(username)) {
      warnings.add("The username '" + username + "' looks like a superuser account. "
          + "Prefer a read-only database user with access to only the schemas you want to query.");
    }

    DataSource record = new DataSource();
    record.setOwnerUserId(ownerUserId);
    record.setName(name.trim());
    record.setEngine(engine);
    record.setJdbcUrlEnc(crypto.encrypt(jdbcUrl.trim()));
    record.setUsernameEnc(username == null ? null : crypto.encrypt(username));
    record.setPasswordEnc(password == null ? null : crypto.encrypt(password));
    repository.save(record);
    return new AddResult(toView(record), warnings);
  }

  /** Re-test the stored connection of an owned data source. */
  @Transactional(readOnly = true)
  public List<String> testConnection(UUID id, String ownerUserId) {
    DataSource record = requireOwned(id, ownerUserId);
    if (record.getSandboxSchema() != null) {
      testSandboxConnection(record.getSandboxSchema());
      return List.of();
    }
    testLiveConnection(record.getEngine(), jdbcUrlOf(record), usernameOf(record), passwordOf(record));
    List<String> warnings = new ArrayList<>();
    if (SsrfGuard.looksPrivileged(usernameOf(record))) {
      warnings.add("Stored credentials look like a superuser account; prefer a read-only user.");
    }
    return warnings;
  }

  @Transactional(readOnly = true)
  public DataSourceView get(UUID id, String ownerUserId) {
    return toView(requireOwned(id, ownerUserId));
  }

  @Transactional(readOnly = true)
  public List<DataSourceView> list(String ownerUserId) {
    return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream()
        .map(this::toView).toList();
  }

  /** Delete an owned data source; snapshots and sandbox schemas are cleaned up by event. */
  @Transactional
  public void delete(UUID id, String ownerUserId) {
    DataSource record = requireOwned(id, ownerUserId);
    repository.delete(record);
    events.publishEvent(new DataSourceDeleted(id, ownerUserId, record.getSandboxSchema()));
  }

  /** Decrypted connection params for internal use (gateway, schema sync). */
  @Transactional(readOnly = true)
  public DataSourceAdapter.ConnectionParams connectionParams(UUID id, String ownerUserId) {
    DataSource record = requireOwned(id, ownerUserId);
    return new DataSourceAdapter.ConnectionParams(record.getEngine(), jdbcUrlOf(record),
        usernameOf(record), passwordOf(record), record.getSandboxSchema());
  }

  /** Persist a sandbox source created by SQL-file upload. */
  @Transactional
  public DataSourceView saveSandbox(String ownerUserId, String name, String sandboxSchema) {
    DataSource record = new DataSource();
    record.setOwnerUserId(ownerUserId);
    record.setName(name);
    record.setEngine(Engine.POSTGRES);
    record.setSandboxSchema(sandboxSchema);
    repository.save(record);
    return toView(record);
  }

  private void testLiveConnection(Engine engine, String jdbcUrl, String username, String password) {
    try {
      adapter.testConnection(
          new DataSourceAdapter.ConnectionParams(engine, jdbcUrl, username, password, null));
    } catch (DataSourceAdapter.AdapterException e) {
      throw new DataSourceException(
          DataSourceErrorCode.CONNECTION_FAILED,
          "connection test failed: " + e.getMessage(), e);
    }
  }

  private DataSource requireOwned(UUID id, String ownerUserId) {
    return repository.findByIdAndOwnerUserId(id, ownerUserId)
        .orElseThrow(() -> new DataSourceException(
            DataSourceErrorCode.DATASOURCE_NOT_FOUND,
            "data source not found"));
  }

  private String jdbcUrlOf(DataSource record) {
    return record.getJdbcUrlEnc() == null ? null : crypto.decrypt(record.getJdbcUrlEnc());
  }

  private String usernameOf(DataSource record) {
    return record.getUsernameEnc() == null ? null : crypto.decrypt(record.getUsernameEnc());
  }

  private String passwordOf(DataSource record) {
    return record.getPasswordEnc() == null ? null : crypto.decrypt(record.getPasswordEnc());
  }

  private DataSourceView toView(DataSource record) {
    return new DataSourceView(record.getId(), record.getName(), record.getEngine(),
        record.getSandboxSchema() != null, record.getCreatedAt());
  }

  /** Live introspection used by schema sync (schema column hidden flags merged by caller). */
  @Transactional(readOnly = true)
  public SchemaSnapshot introspect(UUID id, String ownerUserId) {
    DataSource record = requireOwned(id, ownerUserId);
    try {
      if (record.getSandboxSchema() != null) {
        return SandboxIntrospector.introspect(appDataSource, record.getSandboxSchema());
      }
      return adapter.introspect(connectionParams(id, ownerUserId));
    } catch (DataSourceException e) {
      throw e;
    } catch (Exception e) {
      throw new DataSourceException(
          DataSourceErrorCode.INTROSPECTION_FAILED,
          "introspection failed: " + e.getMessage(), e);
    }
  }

  private void testSandboxConnection(String sandboxSchema) {
    try (java.sql.Connection connection = appDataSource.getConnection();
        java.sql.Statement statement = connection.createStatement();
        java.sql.ResultSet tables = connection.getMetaData().getTables(
            connection.getCatalog(), sandboxSchema, "%", new String[] {"TABLE", "VIEW"})) {
      if (!tables.next()) {
        throw new DataSourceException(
            DataSourceErrorCode.CONNECTION_FAILED,
            "sandbox schema is empty or missing");
      }
      statement.executeQuery("SELECT 1");
    } catch (DataSourceException e) {
      throw e;
    } catch (Exception e) {
      throw new DataSourceException(
          DataSourceErrorCode.CONNECTION_FAILED,
          "sandbox connection failed: " + e.getMessage(), e);
    }
  }
}
