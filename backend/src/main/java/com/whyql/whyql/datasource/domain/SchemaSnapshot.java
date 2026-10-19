package com.whyql.whyql.datasource.domain;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time view of a data source's structure. Produced by live introspection
 * (JdbcAdapter), SQL/DDL parsing, code-model parsing, or manual definition — every
 * producer converges on this one shape so downstream modules never care where the
 * schema came from. Per-column {@code hidden} is enforced in code (validator +
 * executor strip), never only in the prompt.
 */
public record SchemaSnapshot(
    String name,
    List<SchemaTable> tables,
    Instant capturedAt,
    List<String> schemas) {

  /**
   * Convenience constructor for producers that don't resolve namespaces
   * (SQL upload, manual definitions, older tests) — {@code schemas} is null
   * and schema-meta answers about schemas fall through to the normal pipeline.
   */
  public SchemaSnapshot(String name, List<SchemaTable> tables, Instant capturedAt) {
    this(name, tables, capturedAt, null);
  }

  public record SchemaTable(
      String name,
      List<SchemaColumn> columns,
      List<String> primaryKeys,
      List<ForeignKey> foreignKeys) {}

  public record SchemaColumn(
      String name,
      String type,
      boolean nullable,
      boolean hidden) {}

  public record ForeignKey(
      List<String> columns,
      String referencedTable,
      List<String> referencedColumns) {}
}
