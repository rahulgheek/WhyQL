package com.whyql.whyql.datasource;

/**
 * Engine abstraction. Other modules use this interface (and the records beside it)
 * and never import adapter implementation classes.
 *
 * <p>Connection handling is deliberately short-lived (DriverManager, not a pool):
 * connections are created dynamically per data source, used once, and closed. A
 * pooled variant can replace the internals later without touching this contract.
 */
public interface DataSourceAdapter {

  /** True if this adapter handles the engine (used to pick JdbcAdapter for SQL). */
  boolean supports(Engine engine);

  /** Open a throwaway connection and run a trivial query; throws on any failure. */
  void testConnection(ConnectionParams params) throws AdapterException;

  /** Read tables, columns, primary keys and foreign keys into a snapshot. */
  SchemaSnapshot introspect(ConnectionParams params) throws AdapterException;

  /**
   * Basic plan checks (engine match, read-only shape). The JSqlParser hard gate
   * (allowlisted tables/columns, function denylist, LIMIT enforcement) lands in
   * Phase 4 in front of this call.
   */
  void validate(QueryPlan plan, SchemaSnapshot snapshot, VisibilityRules rules)
      throws AdapterException;

  /**
   * Run a validated read-only plan: read-only connection, statement timeout, row
   * cap, hidden-column stripping. Never called with unvalidated plans.
   */
  TabularResult execute(QueryPlan plan, ConnectionParams params, ExecutionLimits limits,
      VisibilityRules rules) throws AdapterException;

  /** Decrypted connection material. Plaintext lives only in memory, never in logs. */
  record ConnectionParams(Engine engine, String jdbcUrl, String username, String password,
      String schema) {}

  /** Failure with a stable code so callers map it without parsing messages. */
  class AdapterException extends Exception {
    private final String code;

    public AdapterException(String code, String message) {
      super(message);
      this.code = code;
    }

    public AdapterException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
