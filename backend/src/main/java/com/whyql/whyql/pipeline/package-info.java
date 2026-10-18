/**
 * Pipeline: Kafka-driven, multi-stage question processing.
 *
 * Cross-module surface: only {@code services} (AsyncQueryOrchestrator), the
 * entry point that queues a job and returns immediately. Kafka consumers stay
 * internal; external entry is the api module's GraphQL surface.
 *
 * Stage flow:
 * query.requested -> (schema + LLM generation) -> query.generated
 *   -> (validation + execution) -> query.executed
 *   -> (analysis handoff) -> analysis.requested -> (completed)
 */
@ApplicationModule(displayName = "Pipeline")
package com.whyql.whyql.pipeline;

import org.springframework.modulith.ApplicationModule;
