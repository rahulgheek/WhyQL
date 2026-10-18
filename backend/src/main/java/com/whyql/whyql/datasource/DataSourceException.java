package com.whyql.whyql.datasource;

/**
 * Business failure in datasource/schema handling, tagged with a stable error code.
 * Messages never contain connection strings or decrypted secrets.
 */
public class DataSourceException extends RuntimeException {

  private final DataSourceErrorCode code;

  public DataSourceException(DataSourceErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public DataSourceException(DataSourceErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public DataSourceErrorCode code() {
    return code;
  }
}
