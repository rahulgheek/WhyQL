package com.whyql.whyql.schema.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

import com.whyql.whyql.datasource.domain.SchemaSnapshot;

/**
 * Schema REST shapes. Uploads use multipart file parts (controller level);
 * JSON bodies below cover GitHub sync and manual definition.
 */
public final class SchemaDtos {

  private SchemaDtos() {}

  public record GitHubSyncRequest(
      @NotBlank String name,
      UUID dataSourceId,
      @NotBlank String repoOwner,
      @NotBlank String repoName,
      String ref,
      String pathPrefix,
      String token) {}

  public record ManualSchemaRequest(
      @NotBlank String name,
      UUID dataSourceId,
      List<ManualTable> tables) {}

  public record ManualTable(
      @NotBlank String name,
      List<ManualColumn> columns,
      List<String> primaryKeys,
      List<ManualForeignKey> foreignKeys) {}

  public record ManualColumn(
      @NotBlank String name,
      @NotBlank String type,
      boolean nullable,
      boolean hidden) {}

  public record ManualForeignKey(
      List<String> columns,
      String referencedTable,
      List<String> referencedColumns) {}

  public record SnapshotResponse(
      UUID id,
      UUID dataSourceId,
      String name,
      int version,
      String source,
      SchemaSnapshot snapshot,
      String createdAt) {}

  /** Manual DTOs map onto the shared snapshot shape (hidden flags honored). */
  public static SchemaSnapshot toSnapshot(ManualSchemaRequest request) {
    List<SchemaSnapshot.SchemaTable> tables = request.tables() == null ? List.of()
        : request.tables().stream().map(table -> new SchemaSnapshot.SchemaTable(
            table.name(),
            table.columns() == null ? List.of()
                : table.columns().stream().map(column -> new SchemaSnapshot.SchemaColumn(
                    column.name(), column.type(), column.nullable(), column.hidden())).toList(),
            table.primaryKeys() == null ? List.of() : table.primaryKeys(),
            table.foreignKeys() == null ? List.of()
                : table.foreignKeys().stream().map(fk -> new SchemaSnapshot.ForeignKey(
                    fk.columns() == null ? List.of() : fk.columns(),
                    fk.referencedTable(),
                    fk.referencedColumns() == null ? List.of() : fk.referencedColumns())).toList()))
            .toList();
    return new SchemaSnapshot(request.name(), tables, java.time.Instant.now());
  }
}
