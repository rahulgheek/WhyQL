package com.whyql.whyql.api.model;

import java.util.UUID;

/** GraphQL {@code AskQuestionPayload}: the queued jobId, returned immediately. */
public record AskQuestionPayload(UUID jobId, String status) {
}
