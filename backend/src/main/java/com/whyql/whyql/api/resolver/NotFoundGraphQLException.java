package com.whyql.whyql.api.resolver;

/**
 * Thrown when a referenced entity (e.g. a job id) does not exist or is not
 * observable by this caller. Mapped by {@link ApiExceptionResolver} to a
 * GraphQL error with a NOT_FOUND classification.
 */
public class NotFoundGraphQLException extends RuntimeException {

  public NotFoundGraphQLException(String message) {
    super(message);
  }
}
