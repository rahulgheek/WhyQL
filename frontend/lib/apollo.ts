import {
  ApolloClient,
  InMemoryCache,
  CombinedGraphQLErrors,
  createHttpLink,
  split,
} from "@apollo/client";
import { ErrorLink } from "@apollo/client/link/error";
import { getMainDefinition } from "@apollo/client/utilities";
import { GraphQLWsLink } from "@apollo/client/link/subscriptions";
import { createClient } from "graphql-ws";

import {
  getAccessToken,
  handleSessionExpiredOnce,
  refreshOnce,
  scheduleProactiveRefresh,
} from "@/lib/tokenRefresh";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081/graphql";
const WS_URL = API_URL.replace(/^http/, "ws");

let apolloClient: ApolloClient | undefined;

function withAuthHeaders(options: RequestInit): RequestInit {
  const token = getAccessToken();
  if (!token) return options;
  // Preserve any caller-set headers and append authorization last.
  return {
    ...options,
    headers: {
      ...((options.headers as Record<string, string> | undefined) ?? {}),
      Authorization: `Bearer ${token}`,
    } as Record<string, string>,
  };
}

function createHttpFetch() {
  return async (uri: RequestInfo | URL, options: RequestInit = {}) => {
    const response = await fetch(uri, withAuthHeaders(options));
    if (response.status !== 401) return response;

    // Access token expired mid-session: rotate silently and retry once.
    // Concurrent 401s share a single refresh (no rotation race).
    const revived = await refreshOnce();
    if (revived) {
      const retry = await fetch(uri, withAuthHeaders(options));
      if (retry.status !== 401) return retry;
    }
    // Refresh is dead (or the retry still bounced) — the session is gone.
    handleSessionExpiredOnce();
    return response;
  };
}

/**
 * Bad tokens surface either as HTTP 401 in the fetch above, or as
 * per-operation UNAUTHORIZED GraphQL errors (the WS auth interceptor keeps
 * anonymous sessions open, so guarded resolvers reject each operation rather
 * than killing the socket). Try one silent rotation first; only a known-dead
 * refresh token forwards to the login page.
 */
const errorLink = new ErrorLink(({ error }) => {
  if (!error) return;
  void maybeRecoverFromUnauthorized(error);
});

async function maybeRecoverFromUnauthorized(error: unknown): Promise<void> {
  const messages: string[] = [];
  if (CombinedGraphQLErrors.is(error)) {
    for (const graphQLError of error.errors) {
      const extensions = graphQLError.extensions as {
        errorType?: string;
        code?: string;
      } | undefined;
      if (extensions?.errorType === "UNAUTHORIZED" || extensions?.code === "UNAUTHORIZED") {
        messages.push(graphQLError.message);
        break;
      }
      if (/token has expired|unauthorized/i.test(graphQLError.message)) {
        messages.push(graphQLError.message);
      }
    }
  } else if (error instanceof Error) {
    const status = (error as { statusCode?: number }).statusCode;
    if (status === 401 || /token has expired|unauthorized/i.test(error.message ?? "")) {
      messages.push(error.message);
    }
  }
  if (messages.length === 0) return;
  // One shared rotation attempt; a dead refresh token means the session is over.
  if (!(await refreshOnce())) {
    handleSessionExpiredOnce();
  }
}

export function createApolloClient() {
  if (!apolloClient) {
    const httpLink = createHttpLink({
      uri: API_URL,
      // Bearer tokens, not cookies, authenticate the API (see backend AuthGuards),
      // so no credentials-including cross-origin fetch is required.
      fetch: createHttpFetch(),
    });

    // Live updates ride the backend's graphql-transport-ws WebSocket
    // (spring.graphql.websocket.path=/graphql). Only subscriptions take the
    // WS path; queries and mutations stick to HTTP. The access token travels
    // in the connection_init payload — the backend's
    // WebSocketGraphQlInterceptor reads payload.Authorization and
    // authenticates the whole WS session once, so every (re)connect picks up
    // the latest token from storage.
    const wsLink = new GraphQLWsLink(
      createClient({
        url: WS_URL,
        lazy: true, // no socket until a subscription actually runs
        connectionParams: () => {
          const token = getAccessToken();
          return token ? { Authorization: `Bearer ${token}` } : {};
        },
        on: {
          // The backend closes auth-rejected connections; expired tokens land
          // here on every reconnect attempt until the session is reset.
          closed: (event) => {
            const code = (event as CloseEvent)?.code;
            const reason = (event as CloseEvent)?.reason ?? "";
            if (code === 4401 || code === 4403 || /unauthor|expired|invalid token/i.test(reason)) {
              void recoverFromWsAuthFailure();
            }
          },
          error: (error) => {
            const message =
              error instanceof Error ? error.message : String(error ?? "");
            if (/unauthor|expired|invalid token/i.test(message)) {
              void recoverFromWsAuthFailure();
            }
          },
        },
      })
    );

    const link =
      typeof window === "undefined"
        ? errorLink.concat(httpLink) // SSR never subscribes
        : errorLink.concat(
            split(
              ({ query }) => {
                const def = getMainDefinition(query);
                return def.kind === "OperationDefinition" && def.operation === "subscription";
              },
              wsLink,
              httpLink
            ),
          );

    apolloClient = new ApolloClient({
      link,
      cache: new InMemoryCache(),
      defaultOptions: {
        watchQuery: { fetchPolicy: "cache-and-network" },
        query: { fetchPolicy: "network-only" },
      },
    });

    // Reloaded page with a stored session: restart the renewal loop.
    if (typeof window !== "undefined") {
      scheduleProactiveRefresh();
    }
  }
  return apolloClient;
}

async function recoverFromWsAuthFailure(): Promise<void> {
  // One shared rotation attempt; a dead refresh token ends the session.
  if (!(await refreshOnce())) {
    handleSessionExpiredOnce();
  }
}

export function initializeApollo(initialState: Record<string, unknown> | null = null) {
  const _apolloClient = apolloClient ?? createApolloClient();
  if (initialState) {
    // Apollo's cache shape is opaque to callers (pageProps.apolloState comes
    // from next.js serialized props); the runtime contract is cache.restore.
    _apolloClient.cache.restore(initialState as never);
  }
  if (typeof window === "undefined") return _apolloClient;
  if (!apolloClient) apolloClient = _apolloClient;
  return _apolloClient;
}
