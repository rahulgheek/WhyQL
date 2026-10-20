package com.whyql.whyql.api.model;

import java.util.UUID;

/**
 * GraphQL {@code ChatSession}: a saved conversation for the dashboard's
 * paged chat list. {@code messageCount} is a badge, not page content —
 * messages themselves load paged on open.
 */
public record ChatSession(
    UUID id,
    String title,
    UUID dataSourceId,
    int messageCount,
    String createdAt,
    String updatedAt) {
}
