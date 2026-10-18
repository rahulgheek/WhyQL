package com.whyql.whyql.api.model;

import java.util.UUID;

/**
 * GraphQL {@code Job} — flattened job state for clients. Result rows travel
 * through the {@code Json} scalar so numeric/string cells keep their types.
 */
public record Job(
    UUID id,
    String status,
    int progress,
    String ownerUserId,
    UUID dataSourceId,
    String question,
    String errorMessage,
    QueryResult result,
    String createdAt,
    String updatedAt) {
}
