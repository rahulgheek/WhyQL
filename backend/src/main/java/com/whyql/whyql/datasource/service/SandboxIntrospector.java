package com.whyql.whyql.datasource.service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.whyql.whyql.datasource.SchemaSnapshot;

/**
 * Introspection of an isolated sandbox schema inside the app database, used for
 * SQL-file uploads. Same DatabaseMetaData technique as the JDBC adapter, scoped to
 * one schema; kept separate so sandbox handling never leaks into URL flows.
 */
public final class SandboxIntrospector {

  private SandboxIntrospector() {}

  public static SchemaSnapshot introspect(DataSource appDataSource, String sandboxSchema)
      throws Exception {
    try (Connection connection = appDataSource.getConnection()) {
      DatabaseMetaData meta = connection.getMetaData();
      List<SchemaSnapshot.SchemaTable> tables = new ArrayList<>();
      try (ResultSet tableRs = meta.getTables(connection.getCatalog(), sandboxSchema, "%",
          new String[] {"TABLE", "VIEW"})) {
        while (tableRs.next()) {
          String tableName = tableRs.getString("TABLE_NAME");
          List<SchemaSnapshot.SchemaColumn> columns = new ArrayList<>();
          try (ResultSet colRs = meta.getColumns(connection.getCatalog(), sandboxSchema, tableName, "%")) {
            while (colRs.next()) {
              String type = colRs.getString("TYPE_NAME");
              boolean nullable = colRs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
              columns.add(new SchemaSnapshot.SchemaColumn(colRs.getString("COLUMN_NAME"),
                  type == null ? "UNKNOWN" : type, nullable, false));
            }
          }
          if (columns.isEmpty()) {
            continue;
          }
          List<String> primaryKeys = new ArrayList<>();
          try (ResultSet pkRs = meta.getPrimaryKeys(connection.getCatalog(), sandboxSchema, tableName)) {
            java.util.TreeMap<Short, String> ordered = new java.util.TreeMap<>();
            while (pkRs.next()) {
              ordered.put(pkRs.getShort("KEY_SEQ"), pkRs.getString("COLUMN_NAME"));
            }
            primaryKeys.addAll(ordered.values());
          }
          tables.add(new SchemaSnapshot.SchemaTable(tableName, columns, primaryKeys, List.of()));
        }
      }
      tables.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
      return new SchemaSnapshot(null, tables, Instant.now());
    }
  }
}
