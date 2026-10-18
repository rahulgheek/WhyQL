package com.whyql.whyql.api.model;

import java.util.UUID;

/** GraphQL {@code DashboardCard}: a pinned job result snapshot. */
public record DashboardCard(
    UUID id,
    UUID jobId,
    UUID dataSourceId,
    String question,
    String engine,
    QueryResult result,
    String createdAt) {}
