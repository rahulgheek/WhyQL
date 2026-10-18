package com.whyql.whyql.api.resolver;

/**
 * Thrown when an authenticated caller tries to access another user's data.
 * Mapped by {@link ApiExceptionResolver} to a GraphQL error with a FORBIDDEN
 * classification.
 */
public class ForbiddenGraphQLException extends RuntimeException {

  public ForbiddenGraphQLException(String message) {
    super(message);
  }
}
