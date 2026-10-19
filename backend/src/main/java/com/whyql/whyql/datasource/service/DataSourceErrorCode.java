package com.whyql.whyql.datasource.service;

/**
 * Stable error codes for the datasource module (REST layer maps them to statuses).
 */
public enum DataSourceErrorCode {
  INVALID_URL,
  BLOCKED_HOST,
  UNREACHABLE_HOST,
  CONNECTION_FAILED,
  INTROSPECTION_FAILED,
  EXECUTION_FAILED,
  NOT_READ_ONLY,
  ENGINE_MISMATCH,
  DATASOURCE_NOT_FOUND,
  INVALID_SCHEMA_FILE,
  GITHUB_FETCH_FAILED,
  SCHEMA_PARSE_FAILED,
  SNAPSHOT_NOT_FOUND,
  ENCRYPTION_FAILED
}
