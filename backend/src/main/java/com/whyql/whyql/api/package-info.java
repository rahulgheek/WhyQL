/**
 * Api: the GraphQL surface. Resolvers enforce auth per field (the /graphql
 * endpoint itself stays open for GraphiQL) and delegate to other modules:
 * datasource (data sources), jobs (job state) and pipeline (async entry
 * point). Subscriptions stream job lifecycle events to connected clients.
 */
@ApplicationModule(
    displayName = "Api",
    allowedDependencies = {
        "jobs::domain", "jobs::events", "jobs::services",
        "pipeline::services", "datasource::domain", "datasource::services", "shared",
        "dashboard::services", "dashboard::domain",
        "board::domain", "board::services", "query",
        "chat::domain", "chat::services"
    })
package com.whyql.whyql.api;

import org.springframework.modulith.ApplicationModule;
