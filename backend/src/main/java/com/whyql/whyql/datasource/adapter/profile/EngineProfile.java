package com.whyql.whyql.datasource.adapter.profile;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

import com.whyql.whyql.datasource.domain.Engine;

/**
 * Per-engine SQL profile — the seam where database differences live.
 *
 * <p>The shared JDBC adapter (execution, introspection skeleton, snapshot
 * reading) consults its {@link EngineProfile} for everything that actually
 * differs between databases: driver wiring, connection tuning, which
 * namespaces (PostgreSQL schemas, MySQL databases) exist and which tables the
 * credentials can really SELECT from, qualified naming for colliding table
 * names, and engine-specific guidance text. Adding a new database later means
 * adding one profile implementation — the adapter keeps branching zero.
 *
 * <p>fqNaming note: cross-module prompt code never imports this package
 * (Modulith exposes the datasource module root only); dialect guidance for
 * prompts lives in the module-root {@code EngineDialects}.
 */
public interface EngineProfile {

  /** The engine this profile governs. */
  Engine engine();

  /** Human-readable dialect name — prompts, logs and error messages. */
  String displayName();

  /** The JDBC driver class to load before connecting. */
  String driverClassName();

  /** Namespaces reserved by the database — never introspected, never shown. */
  boolean isSystemNamespace(String namespace);

  /** Engine-specific tuning after connect (PostgreSQL search_path etc.). */
  void tune(Connection connection, String namespace) throws Exception;

  /**
   * Namespaces whose contents the connected credentials may read, sorted with
   * the default one first. A pinned data source schema bypasses this list.
   */
  List<String> readableNamespaces(Connection connection) throws Exception;

  /**
   * Table names inside one namespace the credentials can actually SELECT
   * from, ordered by name. Returning {@code null} falls back to generic JDBC
   * metadata listing (no privilege filter).
   */
  Set<String> readableTables(Connection connection, String namespace) throws Exception;

  /**
   * Qualified display name when two namespaces expose the same table name
   * (PostgreSQL {@code schema.table}); null when the engine cannot
   * qualify (MySQL has no schemas).
   */
  default String duplicateDisplayName(String namespace, String table) {
    return null;
  }

  /** The message when zero tables turn out to be readable. */
  default String nothingReadable() {
    return "No tables are readable with the supplied credentials — "
        + "grant SELECT on the intended tables (or fix the user/password) and try again.";
  }

  /** Engine-specific tuning before a query runs, when no schema is pinned. */
  default void tuneExecution(Connection connection, List<String> namespaces) throws Exception {
    // Engines without namespaces (or without special needs) do nothing.
  }

  /** Double-quoted identifier, quotes doubled inside (safe across engines). */
  static String quoteIdent(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
