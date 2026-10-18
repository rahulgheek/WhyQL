/**
 * Jobs: durable job entities and lifecycle bookkeeping for the async question
 * pipeline. Cross-module surface: {@code domain} (Job, JobStatus),
 * {@code events} (JobEvent, JobTopics) and {@code services}
 * (JobService, JobEventPublisher). Kafka wiring, repository and config are
 * internal.
 */
@ApplicationModule(displayName = "Jobs")
package com.whyql.whyql.jobs;

import org.springframework.modulith.ApplicationModule;
