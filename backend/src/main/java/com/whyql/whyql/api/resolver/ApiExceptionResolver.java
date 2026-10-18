package com.whyql.whyql.api.resolver;

import java.util.List;

import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import reactor.core.publisher.Mono;

/**
 * Maps typed resolver exceptions to proper GraphQL error classifications so
 * clients never see a hidden INTERNAL_ERROR for authorization problems:
 *
 * <ul>
 * <li>{@link UnauthorizedGraphQLException} → UNAUTHORIZED</li>
 * <li>{@link ForbiddenGraphQLException} → FORBIDDEN</li>
 * <li>{@link NotFoundGraphQLException} → NOT_FOUND</li>
 * </ul>
 *
 * Anything unresolved (a genuine server-side failure) stays INTERNAL_ERROR by
 * Spring for GraphQL's default, deliberately not leaking details to clients.
 */
@Component
public class ApiExceptionResolver implements DataFetcherExceptionResolver {

  @Override
  public Mono<List<GraphQLError>> resolveException(Throwable exception,
      DataFetchingEnvironment environment) {
    if (exception instanceof UnauthorizedGraphQLException) {
      return Mono.just(List.of(
          error(ErrorType.UNAUTHORIZED, exception.getMessage(), environment)));
    }
    if (exception instanceof ForbiddenGraphQLException) {
      return Mono.just(List.of(
          error(ErrorType.FORBIDDEN, exception.getMessage(), environment)));
    }
    if (exception instanceof NotFoundGraphQLException) {
      return Mono.just(List.of(
          error(ErrorType.NOT_FOUND, exception.getMessage(), environment)));
    }
    return Mono.empty();
  }

  /**
   * Builds with the fetching environment so the error carries the field's
   * path (attaches cleanly to the response instead of leaving a stray
   * null-value error). The null-environment branch exists for unit tests only.
   */
  private static GraphQLError error(ErrorType type, String message,
      DataFetchingEnvironment environment) {
    if (environment == null) {
      return GraphqlErrorBuilder.newError()
          .errorType(type)
          .message(message)
          .build();
    }
    return GraphqlErrorBuilder.newError(environment)
        .errorType(type)
        .message(message)
        .build();
  }
}
