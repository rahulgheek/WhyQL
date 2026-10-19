package com.whyql.whyql.schema.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.whyql.whyql.datasource.domain.SchemaSnapshot;

/**
 * Mutable builder every parser produces. Table/column order is preserved
 * (insertion order) so snapshots are deterministic and diffable.
 */
public class ParsedSchema {

  private final Map<String, TableBuilder> tables = new LinkedHashMap<>();

  public TableBuilder table(String name) {
    return tables.computeIfAbsent(name, TableBuilder::new);
  }

  public boolean isEmpty() {
    return tables.isEmpty();
  }

  public SchemaSnapshot toSnapshot(String name) {
    List<SchemaSnapshot.SchemaTable> snapshotTables = new ArrayList<>();
    for (TableBuilder table : tables.values()) {
      snapshotTables.add(table.build());
    }
    return new SchemaSnapshot(name, snapshotTables, java.time.Instant.now());
  }

  public static final class TableBuilder {
    private final String name;
    private final Map<String, ColumnBuilder> columns = new LinkedHashMap<>();
    private final List<String> primaryKeys = new ArrayList<>();
    private final List<SchemaSnapshot.ForeignKey> foreignKeys = new ArrayList<>();

    TableBuilder(String name) {
      this.name = name;
    }

    public ColumnBuilder column(String name, String type, boolean nullable) {
      return columns.computeIfAbsent(name, n -> new ColumnBuilder(n, type, nullable));
    }

    public void primaryKey(String... columnNames) {
      for (String column : columnNames) {
        if (!primaryKeys.contains(column)) {
          primaryKeys.add(column);
        }
      }
    }

    public void foreignKey(List<String> columns, String referencedTable,
        List<String> referencedColumns) {
      foreignKeys.add(new SchemaSnapshot.ForeignKey(
          List.copyOf(columns), referencedTable, List.copyOf(referencedColumns)));
    }

    SchemaSnapshot.SchemaTable build() {
      List<SchemaSnapshot.SchemaColumn> snapshotColumns = new ArrayList<>();
      for (ColumnBuilder column : columns.values()) {
        snapshotColumns.add(
            new SchemaSnapshot.SchemaColumn(column.name, column.type, column.nullable, false));
      }
      return new SchemaSnapshot.SchemaTable(name, snapshotColumns, List.copyOf(primaryKeys),
          List.copyOf(foreignKeys));
    }
  }

  public static final class ColumnBuilder {
    private final String name;
    private String type;
    private boolean nullable;

    ColumnBuilder(String name, String type, boolean nullable) {
      this.name = name;
      this.type = type;
      this.nullable = nullable;
    }

    public void refine(String type, boolean nullable) {
      if (this.type == null || this.type.equals("UNKNOWN")) {
        this.type = type;
      }
      this.nullable = this.nullable || nullable;
    }
  }
}
