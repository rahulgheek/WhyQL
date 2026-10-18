package com.whyql.whyql.datasource;

/**
 * Query engines WhyQL can read from. JDBC covers both; MongoDB arrives in its own
 * phase with a dedicated adapter (raw driver, dynamic connections).
 */
public enum Engine {
  POSTGRES,
  MYSQL
}
