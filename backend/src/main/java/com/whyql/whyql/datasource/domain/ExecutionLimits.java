package com.whyql.whyql.datasource.domain;

/**
 * Guard rails for every execution: wall-clock statement timeout and maximum rows
 * returned. Defaults are deliberately small; the query module may tighten them.
 */
public record ExecutionLimits(int timeoutSeconds, int maxRows) {

  public static ExecutionLimits defaults() {
    return new ExecutionLimits(30, 1000);
  }
}
