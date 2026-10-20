package com.whyql.whyql.api.model;

import java.util.List;
import java.util.UUID;

/**
 * GraphQL {@code ChatMessage}: one bubble. Assistant text may be null when
 * the answer is a rich job render ({@code jobId} set, UI loads that job).
 */
public record ChatMessage(
    UUID id,
    String role,
    String text,
    UUID jobId,
    String createdAt) {
}
