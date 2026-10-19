package com.whyql.whyql.datasource.adapter;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.adapter.profile.EngineProfile;
import com.whyql.whyql.datasource.adapter.profile.EngineProfiles;
import com.whyql.whyql.datasource.adapter.DataSourceAdapter;
import com.whyql.whyql.datasource.domain.Engine;
import com.whyql.whyql.datasource.domain.ExecutionLimits;
import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.datasource.domain.VisibilityRules;

/**
 * JDBC adapter for Postgres and MySQL. Connections are short-lived and strictly
 * read-only at execution time (setReadOnly + statement timeout + row cap); no pool
 * is kept because connections are created dynamically per data source.
 *
 * <p>Everything that differs between databases lives in an
 * {@link EngineProfile}: driver wiring, namespace enumeration, SELECT-privilege
 * filtering and per-namespace tuning. This adapter carries the engine-generic
 * machinery — that is what keeps adding a database a one-file change.
 *
 * <p>Read-only enforcement here is structural (statement shape + JDBC read-only);
 * the JSqlParser allowlist gate (tables, columns, functions, LIMIT) lands in
 * Phase 4 in front of execute().
 */
@Component
public class JdbcAdapter implements DataSourceAdapter {

  private static final int LOGIN_TIMEOUT_SECONDS = 5;

  private final EngineProfiles profiles;

  public JdbcAdapter(EngineProfiles profiles) {
    this.profiles = profiles;
  }

  @Override
  public boolean supports(Engine engine) {
    return profiles.supports(engine);
  }

  @Override
  public void testConnection(ConnectionParams params) throws AdapterException {
    requireSupported(params.engine());
    try (Connection connection = open(params, profiles.profile(params.engine()))) {
      try (Statement statement = connection.createStatement();
          ResultSet ignored = statement.executeQuery("SELECT 1")) {
        // Success is reaching here without an exception.
      }
    } catch (AdapterException e) {
      throw e;
    } catch (Exception e) {
      throw new AdapterException("CONNECTION_FAILED",
          "connection failed: " + safeDetail(params, e), e);
    }
  }

  @Override
  public SchemaSnapshot introspect(ConnectionParams params) throws AdapterException {
    EngineProfile profile = profiles.profile(params.engine());
    try (Connection connection = open(params, profile)) {
      DatabaseMetaData meta = connection.getMetaData();

      // A pinned data source schema (sandbox / user choice) narrows the sweep;
      // otherwise every namespace the credentials can read is enumerated —
      // hosted databases routinely expose several.
      List<String> namespaces = params.schema() != null
          ? List.of(params.schema())
          : profile.readableNamespaces(connection);

      List<SchemaSnapshot.SchemaTable> tables = new ArrayList<>();
      Set<String> seenTableNames = new LinkedHashSet<>();
      List<String> sweptNamespaces = new ArrayList<>();
      for (String namespace : namespaces) {
        if (!profile.isSystemNamespace(namespace)) {
          sweptNamespaces.add(namespace);
          appendReadableTables(meta, connection, profile, namespace, seenTableNames, tables);
        }
      }
      if (tables.isEmpty()) {
        throw new AdapterException("CONNECTION_FAILED", profile.nothingReadable());
      }
      tables.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
      return new SchemaSnapshot(null, tables, java.time.Instant.now(), sweptNamespaces);
    } catch (AdapterException e) {
      throw e;
    } catch (Exception e) {
      throw new AdapterException("INTROSPECTION_FAILED",
          "introspection failed: " + safeDetail(params, e), e);
    }
  }

  /**
   * One namespace → snapshot tables. Column/key metadata always comes from the
   * engine-agnostic JDBC database metadata; the profile decides which tables
   * are visible at all. A table name colliding across namespaces appears once
   * qualified (PostgreSQL: schema.table).
   */
  private void appendReadableTables(DatabaseMetaData meta, Connection connection,
      EngineProfile profile, String namespace, Set<String> seenTableNames,
      List<SchemaSnapshot.SchemaTable> tables) throws Exception {
    Set<String> readableTables = profile.readableTables(connection, namespace);
    if (readableTables == null) {
      // Profile without a native listing: JDBC metadata for this namespace.
      readableTables = new LinkedHashSet<>();
      try (ResultSet tableRs = meta.getTables(
          connection.getCatalog(), namespace, "%", new String[] {"TABLE", "VIEW"})) {
        while (tableRs.next()) {
          String schemaName = tableRs.getString("TABLE_SCHEM");
          if (schemaName == null || schemaName.equals(namespace)) {
            readableTables.add(tableRs.getString("TABLE_NAME"));
          }
        }
      }
    }
    for (String tableName : readableTables) {
      String snapshotName = seenTableNames.contains(tableName)
          ? profile.duplicateDisplayName(namespace, tableName)
          : tableName;
      if (snapshotName == null) {
        continue; // engine cannot qualify the duplicate; keep the first one only
      }
      seenTableNames.add(tableName);

      List<SchemaSnapshot.SchemaColumn> columns =
          readColumns(meta, connection, namespace, tableName);
      if (columns.isEmpty()) {
        continue;
      }
      List<String> primaryKeys = readPrimaryKeys(meta, connection, namespace, tableName);
      List<SchemaSnapshot.ForeignKey> foreignKeys =
          readForeignKeys(meta, connection, namespace, tableName);
      tables.add(new SchemaSnapshot.SchemaTable(snapshotName, columns, primaryKeys, foreignKeys));
    }
  }

  @Override
  public void validate(QueryPlan plan, SchemaSnapshot snapshot, VisibilityRules rules)
      throws AdapterException {
    profiles.profile(plan.engine()); // throws ENGINE_MISMATCH when unsupported
    requireReadOnlyShape(plan.queryText());
  }

  @Override
  public TabularResult execute(QueryPlan plan, ConnectionParams params, ExecutionLimits limits,
      VisibilityRules rules) throws AdapterException {
    EngineProfile profile = profiles.profile(params.engine());
    requireReadOnlyShape(plan.queryText());
    try (Connection connection = open(params, profile)) {
      connection.setReadOnly(true);
      try (Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(limits.timeoutSeconds());
        statement.setMaxRows(limits.maxRows() + 1); // +1 detects truncation
        try (ResultSet rs = statement.executeQuery(plan.queryText())) {
          ResultSetMetaData meta = rs.getMetaData();
          int columnCount = meta.getColumnCount();
          List<TabularResult.ResultColumn> columns = new ArrayList<>();
          List<Integer> kept = new ArrayList<>();
          for (int i = 1; i <= columnCount; i++) {
            String label = meta.getColumnLabel(i);
            if (isHidden(label, rules)) {
              continue; // second enforcement layer behind the Phase 4 validator
            }
            kept.add(i);
            columns.add(new TabularResult.ResultColumn(label, meta.getColumnTypeName(i)));
          }
          List<List<Object>> rows = new ArrayList<>();
          while (rs.next()) {
            List<Object> row = new ArrayList<>(kept.size());
            for (int index : kept) {
              row.add(normalizeCell(rs.getObject(index)));
            }
            rows.add(row);
          }
          boolean truncated = rows.size() > limits.maxRows();
          if (truncated) {
            rows = rows.subList(0, limits.maxRows());
          }
          return new TabularResult(columns, rows, truncated);
        }
      }
    } catch (AdapterException e) {
      throw e;
    } catch (Exception e) {
      throw new AdapterException("EXECUTION_FAILED", "execution failed: " + safeDetail(params, e), e);
    }
  }

  /**
   * Cells must be JSON-friendly scalars: driver-specific wrapper types
   * (PostgreSQL's PGobject for citext/jsonb extensions, java.sql.Array for
   * array columns) would otherwise serialize as {"type":…,"value":…} objects
   * and surface to clients as "[object Object]".
   */
  private static Object normalizeCell(Object raw) {
    if (raw == null || raw instanceof String || raw instanceof Number
        || raw instanceof Boolean || raw instanceof java.util.UUID
        || raw instanceof java.time.temporal.Temporal
        || raw instanceof java.util.Date) {
      return raw;
    }
    if ("org.postgresql.util.PGobject".equals(raw.getClass().getName())) {
      // citext/jsonb/inet: the raw text IS the value. Reflection because the
      // PostgreSQL driver is a runtime-only dependency (drivers never leak
      // into compiled code).
      try {
        Object value = raw.getClass().getMethod("getValue").invoke(raw);
        return value != null ? value : String.valueOf(raw);
      } catch (Exception e) {
        return String.valueOf(raw);
      }
    }
    if (raw instanceof java.sql.Array array) {
      try {
        Object[] elements = (Object[]) array.getArray();
        List<Object> normalized = new ArrayList<>(elements.length);
        for (Object element : elements) {
          normalized.add(normalizeCell(element));
        }
        return normalized;
      } catch (Exception e) {
        return String.valueOf(raw);
      }
    }
    if (raw instanceof byte[] bytes) {
      return java.util.Base64.getEncoder().encodeToString(bytes);
    }
    return String.valueOf(raw); // last resort: driver types print readably
  }

  private Connection open(ConnectionParams params, EngineProfile profile) throws Exception {    Class.forName(profile.driverClassName());
    Properties props = new Properties();
    if (params.username() != null) {
      props.setProperty("user", params.username());
    }
    if (params.password() != null) {
      props.setProperty("password", params.password());
    }
    props.setProperty("loginTimeout", String.valueOf(LOGIN_TIMEOUT_SECONDS));
    props.setProperty("connectTimeout", String.valueOf(LOGIN_TIMEOUT_SECONDS * 1000));
    Connection connection = DriverManager.getConnection(params.jdbcUrl(), props);
    if (params.schema() != null) {
      profile.tune(connection, params.schema());
    } else {
      // Unpinned source: cover every readable schema so unqualified table
      // references validated against the snapshot actually resolve.
      profile.tuneExecution(connection, profile.readableNamespaces(connection));
    }
    return connection;
  }

  private static void requireSupported(Engine engine) throws AdapterException {
    if (engine != Engine.POSTGRES && engine != Engine.MYSQL) {
      throw new AdapterException("ENGINE_MISMATCH", "JDBC adapter cannot run engine " + engine);
    }
  }

  private static void requireReadOnlyShape(String sql) throws AdapterException {
    if (sql == null) {
      throw new AdapterException("NOT_READ_ONLY", "query text is required");
    }
    String normalized = sql.strip().replaceAll("^\\s*\\(.*\\)\\s*", "").toUpperCase(Locale.ROOT);
    if (!(normalized.startsWith("SELECT") || normalized.startsWith("WITH"))) {
      throw new AdapterException("NOT_READ_ONLY", "only SELECT queries may be executed");
    }
  }

  private static List<SchemaSnapshot.SchemaColumn> readColumns(DatabaseMetaData meta,
      Connection connection, String schema, String table) throws Exception {
    List<SchemaSnapshot.SchemaColumn> columns = new ArrayList<>();
    try (ResultSet rs = meta.getColumns(connection.getCatalog(), schema, table, "%")) {
      while (rs.next()) {
        String name = rs.getString("COLUMN_NAME");
        String type = rs.getString("TYPE_NAME");
        boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
        columns.add(new SchemaSnapshot.SchemaColumn(name, type == null ? "UNKNOWN" : type, nullable, false));
      }
    }
    return columns;
  }

  private static List<String> readPrimaryKeys(DatabaseMetaData meta, Connection connection,
      String schema, String table) throws Exception {
    TreeMap<Short, String> ordered = new TreeMap<>();
    try (ResultSet rs = meta.getPrimaryKeys(connection.getCatalog(), schema, table)) {
      while (rs.next()) {
        ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
      }
    }
    return new ArrayList<>(ordered.values());
  }

  private static List<SchemaSnapshot.ForeignKey> readForeignKeys(DatabaseMetaData meta,
      Connection connection, String schema, String table) throws Exception {
    Map<String, ForeignKeyBuilder> builders = new LinkedHashMap<>();
    try (ResultSet rs = meta.getImportedKeys(connection.getCatalog(), schema, table)) {
      while (rs.next()) {
        String fkName = rs.getString("FK_NAME");
        String key = fkName != null ? fkName
            : table + "." + rs.getString("FKCOLUMN_NAME") + "->" + rs.getString("PKTABLE_NAME");
        ForeignKeyBuilder builder = builders.computeIfAbsent(key, k -> new ForeignKeyBuilder());
        short seq = rs.getShort("KEY_SEQ");
        builder.columns.put(seq, rs.getString("FKCOLUMN_NAME"));
        builder.referencedTable = rs.getString("PKTABLE_NAME");
        builder.referencedColumns.put(seq, rs.getString("PKCOLUMN_NAME"));
      }
    }
    List<SchemaSnapshot.ForeignKey> result = new ArrayList<>();
    for (ForeignKeyBuilder builder : builders.values()) {
      result.add(new SchemaSnapshot.ForeignKey(new ArrayList<>(builder.columns.values()),
          builder.referencedTable, new ArrayList<>(builder.referencedColumns.values())));
    }
    return result;
  }

  private static final class ForeignKeyBuilder {
    final TreeMap<Short, String> columns = new TreeMap<>();
    final TreeMap<Short, String> referencedColumns = new TreeMap<>();
    String referencedTable;
  }

  private static boolean isHidden(String label, VisibilityRules rules) {
    if (rules == null || rules.hiddenColumns().isEmpty()) {
      return false;
    }
    for (String hidden : rules.hiddenColumns()) {
      int dot = hidden.lastIndexOf('.');
      String columnPart = dot >= 0 ? hidden.substring(dot + 1) : hidden;
      if (label.equalsIgnoreCase(columnPart)) {
        return true;
      }
    }
    return false;
  }

  /** Driver messages may echo the URL; strip any embedded password before surfacing. */
  private static String safeDetail(ConnectionParams params, Exception e) {
    String message = e.getMessage() == null ? e.toString() : e.getMessage();
    if (params.password() != null && !params.password().isBlank()) {
      message = message.replace(params.password(), "***");
    }
    if (message.length() > 300) {
      message = message.substring(0, 300) + "...";
    }
    return message;
  }
}
