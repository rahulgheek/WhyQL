package com.whyql.whyql.api.resolver;

import java.util.List;
import java.util.Locale;

import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.service.DataSourceErrorCode;
import com.whyql.whyql.datasource.service.DataSourceException;

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
 * <li>{@link DataSourceException} → a classified, user-fixable message</li>
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
    if (exception instanceof DataSourceException dsException) {
      return Mono.just(List.of(error(
          errorType(dsException.code()),
          friendlyMessage(dsException.getMessage()),
          environment)));
    }
    return Mono.empty();
  }

  /**
   * Datasource failures are almost always fixable by the user (wrong
   * password, unreachable host, blocked local address) — never present them
   * as a server error. Security exceptions stay forbidden; missing objects
   * are not found; unreachable servers are classified as service issues.
   */
  private static ErrorType errorType(DataSourceErrorCode code) {
    return switch (code) {
      case DATASOURCE_NOT_FOUND, SNAPSHOT_NOT_FOUND -> ErrorType.NOT_FOUND;
      case BLOCKED_HOST -> ErrorType.FORBIDDEN;
      default -> ErrorType.BAD_REQUEST;
    };
  }

  /**
   * Turns datasource exception chains ('connection test failed: connection
   * failed: ERROR: password authentication failed for user x') into a single
   * actionable sentence. Messages come from the adapter, which reports the
   * host only — never credentials — so translating them stays safe to show.
   */
  static String friendlyMessage(String message) {
    String detail = stripNoise(message);
    String lower = detail.toLowerCase(Locale.ROOT);
    if (lower.contains("password authentication failed for user")
        || lower.contains("access denied for user")) {
      return "The username or password was rejected by the database server. "
          + "Double-check the credentials (and that the account exists).";
    }
    if (lower.contains("unknown database")
        || (lower.contains("database") && lower.contains("does not exist"))) {
      return "That database does not exist on the server — check the database name in the URL.";
    }
    if (lower.contains("no suitable driver")) {
      return "The URL scheme does not match the selected engine — use jdbc:postgresql:// for PostgreSQL or jdbc:mysql:// for MySQL.";
    }
    if (lower.contains("communicat") || lower.contains("connection refused")
        || lower.contains("timed out") || lower.contains("could not connect")) {
      return "Could not reach the database server. Check the host and port, and that "
          + "the server accepts remote connections from this machine (provider IP allowlist / firewall).";
    }
    if (lower.contains("ssl") || lower.contains("tls")) {
      return "The database server requires an encrypted (TLS) connection — add the SSL "
          + "parameter your provider documents (e.g. PostgreSQL: ?sslmode=require).";
    }
    if (lower.contains("cannot resolve database host") || lower.contains("unknownhostexception")) {
      return "The host name could not be resolved — check it for typos.";
    }
    return detail.isBlank() ? "The data source operation failed." : detail;
  }

  /** Peels the generic layer prefixes off so only the meaningful detail remains. */
  private static final String[] NOISE_PREFIXES = {
      "connection test failed: ", "connection failed: ", "introspection failed: ",
      "ERROR: ", "SQLException: ", "PSQLException: ", "SQLSyntaxErrorException: " };

  private static String stripNoise(String message) {
    String result = message == null ? "" : message.trim();
    boolean changed = true;
    while (changed) {
      changed = false;
      for (String prefix : NOISE_PREFIXES) {
        String lowered = result.toLowerCase(Locale.ROOT);
        if (lowered.startsWith(prefix.toLowerCase(Locale.ROOT))) {
          result = result.substring(prefix.length()).trim();
          changed = true;
        }
      }
    }
    return result;
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
