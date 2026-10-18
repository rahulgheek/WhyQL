package com.whyql.whyql.schema.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;

import com.whyql.whyql.datasource.DataSourceErrorCode;
import com.whyql.whyql.datasource.DataSourceException;

/**
 * Parses DDL (.sql files, Flyway migrations) into a ParsedSchema using JSqlParser.
 * Handles CREATE TABLE with inline and table-level primary keys and foreign keys;
 * every other statement (INSERT seeds, indexes, comments) is ignored for the
 * snapshot — seeds still load into sandbox schemas via SandboxService.
 */
@Component
public class SqlSchemaParser {

  public ParsedSchema parse(String sql) {
    if (sql == null || sql.isBlank()) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "SQL schema is empty");
    }
    final Statements statements;
    try {
      statements = CCJSqlParserUtil.parseStatements(sql);
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "could not parse SQL: " + firstLine(e.getMessage()), e);
    }
    ParsedSchema schema = new ParsedSchema();
    for (Statement statement : statements.getStatements()) {
      if (statement instanceof CreateTable createTable) {
        parseCreateTable(createTable, schema);
      }
    }
    if (schema.isEmpty()) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "no CREATE TABLE statements found");
    }
    return schema;
  }

  /** Splits a script into individual statements (shared with sandbox loading). */
  public List<Statement> split(String sql) {
    try {
      return new ArrayList<>(CCJSqlParserUtil.parseStatements(sql).getStatements());
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.SCHEMA_PARSE_FAILED,
          "could not parse SQL: " + firstLine(e.getMessage()), e);
    }
  }

  private void parseCreateTable(CreateTable createTable, ParsedSchema schema) {
    String tableName = stripQuotes(createTable.getTable().getName());
    ParsedSchema.TableBuilder table = schema.table(tableName);

    if (createTable.getColumnDefinitions() != null) {
      for (ColumnDefinition definition : createTable.getColumnDefinitions()) {
        String columnName = stripQuotes(definition.getColumnName());
        String type = definition.getColDataType() == null ? "UNKNOWN"
            : definition.getColDataType().toString();
        List<String> specs = definition.getColumnSpecs() == null ? List.of()
            : definition.getColumnSpecs();
        boolean nullable = !containsNotNull(specs);
        table.column(columnName, type, nullable);
        if (containsPrimaryKey(specs)) {
          table.primaryKey(columnName);
        }
        // Inline REFERENCES col REFERENCES other(col): columnSpecs hold the tokens.
        String referenced = inlineReference(specs);
        if (referenced != null) {
          String[] parts = referenced.split("\\.");
          if (parts.length == 2) {
            table.foreignKey(List.of(columnName), parts[0], List.of(parts[1]));
          }
        }
      }
    }

    if (createTable.getIndexes() != null) {
      for (Index index : createTable.getIndexes()) {
        if (index instanceof ForeignKeyIndex foreignKey) {
          List<String> columns = nullToEmpty(foreignKey.getColumnsNames());
          List<String> referenced = nullToEmpty(foreignKey.getReferencedColumnNames());
          String referencedTable = foreignKey.getTable() == null ? null
              : stripQuotes(foreignKey.getTable().getName());
          if (!columns.isEmpty() && referencedTable != null) {
            table.foreignKey(columns, referencedTable, referenced);
          }
        } else if ("PRIMARY KEY".equalsIgnoreCase(index.getType())) {
          List<String> columns = nullToEmpty(index.getColumnsNames());
          if (!columns.isEmpty()) {
            table.primaryKey(columns.toArray(String[]::new));
          }
        }
      }
    }
  }

  private static boolean containsNotNull(List<String> specs) {
    for (int i = 0; i < specs.size() - 1; i++) {
      if (specs.get(i).equalsIgnoreCase("NOT")
          && specs.get(i + 1).equalsIgnoreCase("NULL")) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsPrimaryKey(List<String> specs) {
    for (int i = 0; i < specs.size() - 1; i++) {
      if (specs.get(i).equalsIgnoreCase("PRIMARY")
          && specs.get(i + 1).equalsIgnoreCase("KEY")) {
        return true;
      }
    }
    return false;
  }

  private static String inlineReference(List<String> specs) {
    for (int i = 0; i < specs.size(); i++) {
      if (specs.get(i).equalsIgnoreCase("REFERENCES") && i + 1 < specs.size()) {
        String target = stripQuotes(specs.get(i + 1));
        String columns = "";
        if (i + 2 < specs.size() && specs.get(i + 2).startsWith("(")) {
          columns = specs.get(i + 2).replaceAll("[()]", "").trim();
        }
        String[] columnParts = columns.isEmpty() ? new String[] {"id"} : columns.split(",");
        return target + "." + stripQuotes(columnParts[0].trim());
      }
    }
    return null;
  }

  private static List<String> nullToEmpty(List<String> values) {
    return values == null ? List.of() : values.stream().map(SqlSchemaParser::stripQuotes).toList();
  }

  private static String stripQuotes(String identifier) {
    if (identifier == null) {
      return null;
    }
    String stripped = identifier.trim();
    if (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
      stripped = stripped.substring(1, stripped.length() - 1);
    }
    return stripped;
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "parse error";
    }
    String flat = message.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
  }
}
