package com.whyql.whyql.schema.service;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whyql.whyql.datasource.DataSourceErrorCode;
import com.whyql.whyql.datasource.DataSourceException;
import com.whyql.whyql.schema.parse.SqlSchemaParser;

/**
 * Loads a SQL file (DDL + optional seed INSERTs) into an isolated sandbox schema
 * inside the app database. Only CREATE TABLE, CREATE INDEX and INSERT statements
 * run; anything else (drops, grants, COPY, functions, transaction control)
 * rejects the whole file before anything executes. Runs in one transaction with a
 * generated schema name, so a failure leaves no residue.
 */
@Service
public class SandboxService {

  private final SqlSchemaParser sqlParser;
  private final DataSource appDataSource;

  public SandboxService(SqlSchemaParser sqlParser, DataSource appDataSource) {
    this.sqlParser = sqlParser;
    this.appDataSource = appDataSource;
  }

  /** Loads the file, returns the generated sandbox schema name. */
  @Transactional
  public String load(String sql) {
    List<net.sf.jsqlparser.statement.Statement> statements = sqlParser.split(sql);
    if (statements.isEmpty()) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE, "SQL file is empty");
    }
    for (net.sf.jsqlparser.statement.Statement statement : statements) {
      if (!(statement instanceof net.sf.jsqlparser.statement.create.table.CreateTable)
          && !(statement instanceof net.sf.jsqlparser.statement.create.index.CreateIndex)
          && !(statement instanceof net.sf.jsqlparser.statement.insert.Insert)) {
        throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
            "sandbox loading allows only CREATE TABLE, CREATE INDEX and INSERT; found: "
                + statement.getClass().getSimpleName());
      }
    }
    String schema = "sandbox_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (Connection connection = appDataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement create = connection.createStatement()) {
        create.execute("CREATE SCHEMA " + quoteIdent(schema));
      }
      try (Statement use = connection.createStatement()) {
        use.execute("SET LOCAL search_path TO " + quoteIdent(schema));
        for (net.sf.jsqlparser.statement.Statement statement : statements) {
          use.execute(statement.toString());
        }
      }
      connection.commit();
      return schema;
    } catch (DataSourceException e) {
      throw e;
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.INVALID_SCHEMA_FILE,
          "sandbox loading failed: " + e.getMessage(), e);
    }
  }

  /** Drops a sandbox schema on data-source deletion (best effort). */
  public void drop(String sandboxSchema) {
    if (sandboxSchema == null || !sandboxSchema.matches("sandbox_[0-9a-f]{12}")) {
      return;
    }
    try (Connection connection = appDataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + quoteIdent(sandboxSchema) + " CASCADE");
    } catch (Exception e) {
      throw new DataSourceException(DataSourceErrorCode.EXECUTION_FAILED,
          "could not drop sandbox schema", e);
    }
  }

  private static String quoteIdent(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
