package com.whyql.whyql.datasource.adapter.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.domain.Engine;

/**
 * PostgreSQL profile. Real catalog introspection instead of generic JDBC
 * metadata: every non-system schema is enumerated, and only tables on which
 * the connecting credentials hold SELECT privilege are reported — hosted
 * Postgres (Neon, Supabase, RDS) routinely exposes other members' schemas
 * this principal cannot read.
 */
@Component
public class PostgresProfile implements EngineProfile {

  @Override
  public Engine engine() {
    return Engine.POSTGRES;
  }

  @Override
  public String displayName() {
    return "PostgreSQL";
  }

  @Override
  public String driverClassName() {
    return "org.postgresql.Driver";
  }

  @Override
  public boolean isSystemNamespace(String namespace) {
    return namespace == null
        || namespace.equals("information_schema")
        || namespace.startsWith("pg_");
  }

  @Override
  public void tune(Connection connection, String namespace) throws Exception {
    if (namespace == null || namespace.isBlank()) {
      return;
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + quoteIdent(namespace));
    }
  }

  /**
   * Execution search path for unpinned data sources: every readable schema
   * (public first), so schema-allowlisted unqualified table references —
   * "users" living in an auxiliary schema, not public — resolve instead of
   * failing with 'relation "users" does not exist'.
   */
  @Override
  public void tuneExecution(Connection connection, List<String> namespaces) throws Exception {
    if (namespaces == null || namespaces.isEmpty()) {
      return;
    }
    String path = namespaces.stream()
        .map(EngineProfile::quoteIdent)
        .collect(Collectors.joining(", "));
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + path);
    }
  }

  /**
   * All non-system namespaces (schemas from other roles included), public
   * first. Table-level privilege decides readability per table.
   */
  @Override
  public List<String> readableNamespaces(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(
            "SELECT nspname FROM pg_namespace "
                + "WHERE nspname = 'public' OR (nspname NOT LIKE 'pg\\_%' "
                + "AND nspname <> 'information_schema') "
                + "ORDER BY (nspname = 'public') DESC, nspname")) {
      List<String> namespaces = new ArrayList<>();
      while (rs.next()) {
        namespaces.add(rs.getString(1));
      }
      return namespaces;
    }
  }

  /**
   * SELECT-privileged relations only: regular tables (r), views (v),
   * partitioned (p), materialized views (m). Tables the credentials cannot
   * read are invisible here — that is what makes Neon's multi-schema
   * databases show exactly what this principal may query.
   */
  @Override
  public Set<String> readableTables(Connection connection, String namespace) throws Exception {
    String sql = "SELECT c.relname FROM pg_class c "
        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
        + "WHERE n.nspname = ? AND c.relkind IN ('r', 'v', 'p', 'm') "
        + "AND has_table_privilege(c.oid, 'SELECT')";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, namespace);
      try (ResultSet rs = ps.executeQuery()) {
        Set<String> tables = new LinkedHashSet<>();
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
        return tables;
      }
    }
  }

  @Override
  public String duplicateDisplayName(String namespace, String table) {
    return namespace + "." + table;
  }

  @Override
  public String nothingReadable() {
    return "PostgreSQL reports no tables this account may read. In hosted "
        + "databases (Neon, Supabase, RDS) each schema may belong to another "
        + "role — grant SELECT on the intended tables to this user, or connect "
        + "with the database owner's credentials.";
  }

  private static String quoteIdent(String identifier) {
    return EngineProfile.quoteIdent(identifier);
  }
}
