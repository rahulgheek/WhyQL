package com.whyql.whyql.auth.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.whyql.whyql.auth.service.AuthErrorCode;
import com.whyql.whyql.auth.service.AuthException;

/**
 * Consistent auth error model: every failure is {"code","message"} with a status
 * derived from the stable AuthErrorCode. Also reused by the OAuth2 success handler
 * so callback failures use the same codes.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<Map<String, String>> handleAuth(AuthException e) {
    return ResponseEntity.status(statusFor(e.code()))
        .body(Map.of("code", e.code().name(), "message", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + " " + error.getDefaultMessage())
        .sorted()
        .findFirst()
        .orElse("invalid request");
    return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED", "message", message));
  }

  /** Shared status mapping so REST and OAuth-callback failures stay consistent. */
  public static HttpStatus statusFor(AuthErrorCode code) {
    return switch (code) {
      case USER_ALREADY_EXISTS -> HttpStatus.CONFLICT;
      case USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN, INVALID_ACCESS_TOKEN -> HttpStatus.UNAUTHORIZED;
      case ACCOUNT_NOT_VERIFIED -> HttpStatus.FORBIDDEN;
      case OTP_ATTEMPTS_EXCEEDED, RESEND_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
      case EMAIL_SEND_FAILED -> HttpStatus.BAD_GATEWAY;
      case OAUTH_FAILED -> HttpStatus.BAD_GATEWAY;
      default -> HttpStatus.BAD_REQUEST;
    };
  }
}
