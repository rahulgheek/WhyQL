package com.whyql.whyql.auth.service;

/**
 * Business failure in the auth flow, always tagged with a stable error code.
 */
public class AuthException extends RuntimeException {

  private final AuthErrorCode code;

  public AuthException(AuthErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public AuthException(AuthErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public AuthErrorCode code() {
    return code;
  }
}
