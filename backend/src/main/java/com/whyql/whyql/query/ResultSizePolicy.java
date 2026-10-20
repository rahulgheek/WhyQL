package com.whyql.whyql.query;

/**
 * The display-size policy for result sections. The executor's own cap
 * (ExecutionLimits.maxRows = 1000) protects the database; this governs what
 * the user is handed: tables above {@link #TABLE_ROW_LIMIT} rows are withheld
 * in favor of a "too large" answer with the exact count — charts and
 * aggregated questions stay small by nature, which is the point.
 */
public final class ResultSizePolicy {

  /** Table rows a section may expose; above this the section is TOO_LARGE. */
  public static final int TABLE_ROW_LIMIT = 200;

  private ResultSizePolicy() {
  }
}
