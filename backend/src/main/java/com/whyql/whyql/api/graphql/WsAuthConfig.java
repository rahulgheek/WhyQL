package com.whyql.whyql.api.graphql;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import com.whyql.whyql.shared.JwtService;

import reactor.core.publisher.Mono;

/**
 * Bridges graphql-transport-ws {@code connection_init} payloads to resolver
 * authentication. The Apollo-style client sends
 * {@code {"payload": {"Authorization": "Bearer <token>"}}}; the JWT is
 * validated once per session and the resulting {@link Authentication} is
 * injected into every operation's GraphQL context
 * ({@key AUTHENTICATION}), where resolvers read it with
 * {@code @ContextValue}. This sidesteps the thread-local/reactive context
 * bridging between the WS handshake (which runs through the HTTP filter
 * chain and leaves an anonymous token behind) and the operation execution.
 *
 * Anonymous sessions are accepted (GraphiQL) but get an anonymous token, so
 * guarded resolvers still reject them.
 */
@Configuration
public class WsAuthConfig {

  private static final Logger log = LoggerFactory.getLogger(WsAuthConfig.class);

  /** GraphQL-context key under which the Authentication is injected. */
  public static final String AUTHENTICATION = "whyql.authentication";

  static final String SESSION_AUTH_KEY = WsAuthConfig.class.getName() + ".AUTHENTICATION";

  @Bean
  WebSocketGraphQlInterceptor webSocketAuthInterceptor(JwtService jwtService) {
    return new WebSocketGraphQlInterceptor() {

      @Override
      public Mono<Object> handleConnectionInitialization(WebSocketSessionInfo sessionInfo,
          Map<String, Object> connectionInitPayload) {
        Object value = connectionInitPayload.get("Authorization");
        if (value instanceof String header && header.startsWith("Bearer ")) {
          try {
            String subject = jwtService.verifyAccessToken(header.substring(7)).getSubject();
            sessionInfo.getAttributes().put(SESSION_AUTH_KEY,
                UsernamePasswordAuthenticationToken.authenticated(subject, null, List.of()));
            log.info("WS session authenticated as {}", subject);
          } catch (Exception e) {
            log.warn("WS token rejected: {}", e.getMessage());
          }
        }
        return Mono.just(Map.of());
      }

      @Override
      public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request,
          WebGraphQlInterceptor.Chain chain) {
        if (request instanceof WebSocketGraphQlRequest wsRequest) {
          Authentication sessionAuth =
              (Authentication) wsRequest.getSessionInfo().getAttributes().get(SESSION_AUTH_KEY);
          Authentication authentication = sessionAuth != null ? sessionAuth
              : new AnonymousAuthenticationToken(
                  "ws", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
          request.configureExecutionInput((executionInput, builder) ->
              builder.graphQLContext(context -> context.put(AUTHENTICATION, authentication))
                  .build());
        }
        return chain.next(request);
      }
    };
  }
}
