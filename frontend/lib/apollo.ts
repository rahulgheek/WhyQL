import {
  ApolloClient,
  InMemoryCache,
  createHttpLink,
  split,
} from "@apollo/client";
import { getMainDefinition } from "@apollo/client/utilities";
import { GraphQLWsLink } from "@apollo/client/link/subscriptions";
import { createClient } from "graphql-ws";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/graphql";
const WS_URL = API_URL.replace(/^http/, "ws");

let apolloClient: ApolloClient | undefined;

export function createApolloClient() {
  if (!apolloClient) {
    const httpLink = createHttpLink({
      uri: API_URL,
      // Bearer tokens, not cookies, authenticate the API (see backend AuthGuards),
      // so no credentials-including cross-origin fetch is required.
      fetch: (uri, options = {}) => {
        const token = typeof window !== "undefined"
          ? localStorage.getItem("access_token")
          : null;
        const headers: Record<string, string> = {
          ...Object.fromEntries(
            options.headers ? new Headers(options.headers as HeadersInit).entries() : []
          ),
        };
        if (token) headers.Authorization = `Bearer ${token}`;
        return fetch(uri, { ...options, headers });
      },
    });

    // Live updates ride the backend's graphql-transport-ws WebSocket
    // (spring.graphql.websocket.path=/graphql). Only subscriptions take the WS
    // path; queries and mutations stick to HTTP. The access token travels in
    // the connection_init payload — the backend's WebSocketGraphQlInterceptor
    // reads payload.Authorization and authenticates the whole WS session once.
    const wsLink = new GraphQLWsLink(
      createClient({
        url: WS_URL,
        lazy: true, // no socket until a subscription actually runs
        connectionParams: () => {
          const token = typeof window !== "undefined"
            ? localStorage.getItem("access_token")
            : null;
          return token ? { Authorization: `Bearer ${token}` } : {};
        },
      })
    );

    const link =
      typeof window === "undefined"
        ? httpLink // SSR never subscribes
        : split(
            ({ query }) => {
              const def = getMainDefinition(query);
              return def.kind === "OperationDefinition" && def.operation === "subscription";
            },
            wsLink,
            httpLink
          );

    apolloClient = new ApolloClient({
      link,
      cache: new InMemoryCache(),
      defaultOptions: {
        watchQuery: { fetchPolicy: "cache-and-network" },
        query: { fetchPolicy: "network-only" },
      },
    });
  }
  return apolloClient;
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
