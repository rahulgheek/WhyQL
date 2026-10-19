package com.whyql.whyql.api.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.graphql.execution.ErrorType;

import graphql.GraphQLError;

/**
 * Verifies the exception → GraphQL error classification mapping: auth failures
 * surface as UNAUTHORIZED/FORBIDDEN/NOT_FOUND, everything else stays
 * unresolved (i.e. Spring's INTERNAL_ERROR default) without leaking details.
 */
class ApiExceptionResolverTest {

  private final ApiExceptionResolver resolver = new ApiExceptionResolver();

  @Test
  void unauthorizedMapsToUnauthorizedClassification() {
    GraphQLError error = resolver.resolveException(
        new UnauthorizedGraphQLException(), null).block().get(0);

    assertThat(error.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
    assertThat(error.getMessage()).isEqualTo("Unauthorized");
  }

  @Test
  void forbiddenMapsToForbiddenClassification() {
    GraphQLError error = resolver.resolveException(
        new ForbiddenGraphQLException("Forbidden: job x"), null).block().get(0);

    assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
    assertThat(error.getMessage()).isEqualTo("Forbidden: job x");
  }

  @Test
  void notFoundMapsToNotFoundClassification() {
    GraphQLError error = resolver.resolveException(
        new NotFoundGraphQLException("Job not found: x"), null).block().get(0);

    assertThat(error.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
    assertThat(error.getMessage()).isEqualTo("Job not found: x");
  }

  @Test
  void unrelatedExceptionsStayUnresolvedInternalErrors() {
    var resolved = resolver.resolveException(
        new IllegalStateException("db explosion"), null).block();

    assertThat(resolved).isNull();
  }

  @Test
  void datasourcePasswordFailureIsClassifiedAndExplained() {
    GraphQLError error = resolver.resolveException(
        new com.whyql.whyql.datasource.service.DataSourceException(
            com.whyql.whyql.datasource.service.DataSourceErrorCode.CONNECTION_FAILED,
            "connection test failed: connection failed: ERROR: password authentication failed for user 'neon_db'"),
        null).block().get(0);

    assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    assertThat(error.getMessage())
        .contains("username or password was rejected")
        .doesNotContain("connection test failed");
  }

  @Test
  void datasourceBlockedHostIsForbidden() {
    GraphQLError error = resolver.resolveException(
        new com.whyql.whyql.datasource.service.DataSourceException(
            com.whyql.whyql.datasource.service.DataSourceErrorCode.BLOCKED_HOST,
            "database host resolves to a blocked (internal) address: localhost"),
        null).block().get(0);

    assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
  }

  @Test
  void datasourceUnknownDatabaseIsExplained() {
    GraphQLError error = resolver.resolveException(
        new com.whyql.whyql.datasource.service.DataSourceException(
            com.whyql.whyql.datasource.service.DataSourceErrorCode.CONNECTION_FAILED,
            "connection test failed: connection failed: FATAL: database \"nope\" does not exist"),
        null).block().get(0);

    assertThat(error.getMessage()).contains("does not exist on the server");
  }
}
