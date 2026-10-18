"use client";

import { useEffect } from "react";
import { ApolloProvider } from "@apollo/client/react";
import { initializeApollo } from "@/lib/apollo";

export default function ApolloClientProvider({
  children,
  pageProps,
}: {
  children: React.ReactNode;
  pageProps?: any;
}) {
  const apolloClient = initializeApollo(pageProps?.apolloState);
  return <ApolloProvider client={apolloClient}>{children}</ApolloProvider>;
}
