package com.whyql.whyql.api.model;

import java.util.List;
import java.util.UUID;

/**
 * GraphQL {@code Job} — flattened job state for clients. Result rows travel
 * through the {@code Json} scalar so numeric/string cells keep their types.
 * clarifyingQuestions carries the LLM's follow-up questions when the job is
 * NEEDS_CLARIFICATION (parsed from the stored query plan).
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
    String analysis,
    List<String> clarifyingQuestions,
    String createdAt,
    String updatedAt) {
}
