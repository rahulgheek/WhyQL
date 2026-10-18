package com.whyql.whyql.datasource.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.whyql.whyql.datasource.DataSourceErrorCode;
import com.whyql.whyql.datasource.DataSourceException;

/** Consistent error model for datasource/schema failures: {"code","message"}. */
@RestControllerAdvice
public class DataSourceExceptionHandler {

  @ExceptionHandler(DataSourceException.class)
  public ResponseEntity<Map<String, String>> handle(DataSourceException e) {
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

  public static HttpStatus statusFor(DataSourceErrorCode code) {
    return switch (code) {
      case DATASOURCE_NOT_FOUND, SNAPSHOT_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case BLOCKED_HOST, UNREACHABLE_HOST, CONNECTION_FAILED -> HttpStatus.BAD_GATEWAY;
      case NOT_READ_ONLY, ENGINE_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
      default -> HttpStatus.BAD_REQUEST;
    };
  }
}
