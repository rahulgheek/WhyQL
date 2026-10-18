package com.whyql.whyql.api.resolver;

/**
 * Thrown by resolvers when the caller is not authenticated. Mapped by
 * {@link ApiExceptionResolver} to a GraphQL error with an UNAUTHORIZED
 * classification — never a hidden INTERNAL_ERROR.
 */
public class UnauthorizedGraphQLException extends RuntimeException {

  public UnauthorizedGraphQLException() {
    super("Unauthorized");
  }
}
