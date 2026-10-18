package com.whyql.whyql.datasource.adapter;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.DataSourceAdapter;
import com.whyql.whyql.datasource.Engine;
import com.whyql.whyql.datasource.ExecutionLimits;
import com.whyql.whyql.datasource.QueryPlan;
import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.TabularResult;
import com.whyql.whyql.datasource.VisibilityRules;

/**
 * JDBC adapter for Postgres and MySQL. Connections are short-lived and strictly
 * read-only at execution time (setReadOnly + statement timeout + row cap); no pool
 * is kept because connections are created dynamically per data source.
 *
 * <p>Read-only enforcement here is structural (statement shape + JDBC read-only);
 * the JSqlParser allowlist gate (tables, columns, functions, LIMIT) lands in
 * Phase 4 in front of execute(). Known gap until then: a WITH...INSERT/UPDATE
 * query passes the shape check — the JDBC read-only flag still blocks the write
 * on Postgres, but the error surfaces as a failure, not a clean rejection.
 */
@Component
public class JdbcAdapter implements DataSourceAdapter {

  private static final int LOGIN_TIMEOUT_SECONDS = 5;

  @Override
  public boolean supports(Engine engine) {
    return engine == Engine.POSTGRES || engine == Engine.MYSQL;
  }

  @Override
  public void testConnection(ConnectionParams params) throws AdapterException {
    requireSupported(params.engine());
    try (Connection connection = open(params)) {
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
    requireSupported(params.engine());
    try (Connection connection = open(params)) {
      DatabaseMetaData meta = connection.getMetaData();
      String schemaPattern = params.schema() != null ? params.schema() : defaultSchema(connection, params.engine());

      List<SchemaSnapshot.SchemaTable> tables = new ArrayList<>();
      try (ResultSet tableRs = meta.getTables(connection.getCatalog(), schemaPattern, "%",
          new String[] {"TABLE", "VIEW"})) {
        while (tableRs.next()) {
          String schema = tableRs.getString("TABLE_SCHEM");
          String tableName = tableRs.getString("TABLE_NAME");
          if (isSystemSchema(params.engine(), schema)) {
            continue;
          }
          List<SchemaSnapshot.SchemaColumn> columns = readColumns(meta, connection, schema, tableName);
          if (columns.isEmpty()) {
            continue;
          }
          List<String> primaryKeys = readPrimaryKeys(meta, connection, schema, tableName);
          List<SchemaSnapshot.ForeignKey> foreignKeys =
              readForeignKeys(meta, connection, schema, tableName);
          tables.add(new SchemaSnapshot.SchemaTable(tableName, columns, primaryKeys, foreignKeys));
        }
      }
      tables.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
      return new SchemaSnapshot(null, tables, java.time.Instant.now());
    } catch (AdapterException e) {
      throw e;
    } catch (Exception e) {
      throw new AdapterException("INTROSPECTION_FAILED",
          "introspection failed: " + safeDetail(params, e), e);
    }
  }

  @Override
  public void validate(QueryPlan plan, SchemaSnapshot snapshot, VisibilityRules rules)
      throws AdapterException {
    if (plan.engine() != Engine.POSTGRES && plan.engine() != Engine.MYSQL) {
      throw new AdapterException("ENGINE_MISMATCH", "JDBC adapter cannot run engine " + plan.engine());
    }
    requireReadOnlyShape(plan.queryText());
  }

  @Override
  public TabularResult execute(QueryPlan plan, ConnectionParams params, ExecutionLimits limits,
      VisibilityRules rules) throws AdapterException {
    requireSupported(params.engine());
    requireReadOnlyShape(plan.queryText());
    try (Connection connection = open(params)) {
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
              row.add(rs.getObject(index));
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

  private Connection open(ConnectionParams params) throws Exception {
    if (params.engine() == Engine.POSTGRES) {
      Class.forName("org.postgresql.Driver");
    } else {
      Class.forName("com.mysql.cj.jdbc.Driver");
    }
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
    if (params.schema() != null && params.engine() == Engine.POSTGRES) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET search_path TO " + quoteIdent(params.schema()));
      }
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

  private static String defaultSchema(Connection connection, Engine engine) throws Exception {
    if (engine == Engine.POSTGRES) {
      return "public";
    }
    String catalog = connection.getCatalog();
    return catalog != null ? catalog : "%";
  }

  private static boolean isSystemSchema(Engine engine, String schema) {
    if (schema == null) {
      return false;
    }
    if (engine == Engine.POSTGRES) {
      return schema.equals("pg_catalog") || schema.equals("information_schema");
    }
    return schema.equals("information_schema") || schema.equals("mysql")
        || schema.equals("performance_schema") || schema.equals("sys");
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

  private static String quoteIdent(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
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
