package com.whyql.whyql.query.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.SchemaSnapshot.SchemaTable;

/**
 * Model tools available to the LLM during query generation. The model may call
 * these to inspect the schema before producing a QueryPlan. Execution always
 * goes through the validator — the model never gets direct execution.
 */
@Component
public class QueryTools {

  private SchemaSnapshot currentSnapshot;

  public void setSnapshot(SchemaSnapshot snapshot) {
    this.currentSnapshot = snapshot;
  }

  /**
   * List all table names in the current schema.
   */
  @Tool(name = "listTables", description = "List all available tables in the current data source schema")
  public List<String> listTables() {
    if (currentSnapshot == null || currentSnapshot.tables() == null) {
      return List.of();
    }
    return currentSnapshot.tables().stream()
        .map(SchemaTable::name)
        .toList();
  }

  /**
   * Describe a table: columns, types, nullability, hidden flags.
   */
  @Tool(name = "describeTable", description = "Get column details for a specific table")
  public Map<String, Object> describeTable(
      @ToolParam(description = "Name of the table to describe") String tableName) {
    if (currentSnapshot == null || currentSnapshot.tables() == null) {
      return Map.of("error", "no schema available");
    }
    for (var table : currentSnapshot.tables()) {
      if (table.name().equalsIgnoreCase(tableName)) {
        return Map.of(
            "name", table.name(),
            "columns", table.columns(),
            "primaryKeys", table.primaryKeys(),
            "foreignKeys", table.foreignKeys());
      }
    }
    return Map.of("error", "table not found: " + tableName);
  }

  /**
   * Get sample values for a column to help the model understand data patterns.
   */
  @Tool(name = "sampleValues", description = "Get sample values for a column (preview only)")
  public List<String> sampleValues(
      @ToolParam(description = "Table name") String tableName,
      @ToolParam(description = "Column name") String columnName) {
    // Sample values are not available at prompt time without executing a query.
    // Return a hint to the model instead.
    return List.of("(sample values not available at prompt time; use describeTable for column info)");
  }

  /**
   * Validate a SQL query string without executing it.
   */
  @Tool(name = "validateSql", description = "Validate a SQL query string for syntax and safety (read-only check)")
  public Map<String, Object> validateSql(
      @ToolParam(description = "SQL query to validate") String sql) {
    // Basic validation hint - the real validation happens in SqlValidator
    boolean isSelect = sql != null && sql.trim().toUpperCase().startsWith("SELECT");
    boolean hasLimit = sql != null && sql.toUpperCase().contains("LIMIT");
    return Map.of(
        "valid", isSelect && hasLimit,
        "hint", isSelect ? "looks like a read-only SELECT" : "not a SELECT statement",
        "hasLimit", hasLimit);
  }
}
