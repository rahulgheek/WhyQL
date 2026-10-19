package com.whyql.whyql.jobs.event;

import java.time.Instant;
import java.util.UUID;

import com.whyql.whyql.jobs.domain.JobStatus;

/**
 * Event published when a job transitions to a new stage. Used for Kafka-based
 * stage-driven processing.
 */
public record JobEvent(
    UUID jobId,
    String ownerUserId,
    UUID dataSourceId,
    String question,
    JobStatus status,
    String errorMessage,
    String queryPlanJson,
    String resultJson,
    String analysis,
    String route,
    Instant timestamp
) {
}
