package com.whyql.whyql.jobs.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;

import tools.jackson.databind.ObjectMapper;

/**
 * Publishes {@link JobEvent}s to stage topics. If the stage send fails after
 * retries, the event is re-sent to the stage's DLQ topic so the job can be
 * inspected instead of silently dropped.
 */
@Component
public class JobEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(JobEventPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher applicationEvents;

  public JobEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper, ApplicationEventPublisher applicationEvents) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.applicationEvents = applicationEvents;
  }

  public void publishQueryRequested(Job job) {
    publish(JobTopics.QUERY_REQUESTED, JobTopics.QUERY_REQUESTED_DLQ,
        event(job, JobStatus.QUEUED, null, null));
  }

  public void publishQueryGenerated(Job job) {
    publish(JobTopics.QUERY_GENERATED, JobTopics.QUERY_GENERATED_DLQ,
        event(job, JobStatus.VALIDATING, null, job.getQueryPlanJson()));
  }

  public void publishQueryExecuted(Job job) {
    publish(JobTopics.QUERY_EXECUTED, JobTopics.QUERY_EXECUTED_DLQ,
        event(job, JobStatus.EXECUTING, null, job.getResultJson()));
  }

  public void publishAnalysisRequested(Job job) {
    publish(JobTopics.ANALYSIS_REQUESTED, JobTopics.ANALYSIS_REQUESTED_DLQ,
        event(job, JobStatus.ANALYZING, null, job.getResultJson()));
  }

  private JobEvent event(Job job, JobStatus status, String errorMessage,
      String queryPlanJson) {
    return new JobEvent(
        job.getId(),
        job.getOwnerUserId(),
        job.getDataSourceId(),
        job.getQuestion(),
        status,
        errorMessage != null ? errorMessage : job.getErrorMessage(),
        queryPlanJson != null ? queryPlanJson : job.getQueryPlanJson(),
        job.getResultJson(),
        Instant.now());
  }

  private void publish(String topic, String dlqTopic, JobEvent event) {
    String key = event.jobId() == null ? null : event.jobId().toString();
    try {
      String payload = objectMapper.writeValueAsString(event);
      kafkaTemplate.send(topic, key, payload);
      log.info("Published {} to {}", key, topic);
    } catch (Exception e) {
      log.error("Publishing to {} failed, falling back to {}", topic, dlqTopic, e);
      try {
        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(dlqTopic, key, payload);
      } catch (Exception dlqFailure) {
        log.error("DLQ fallback to {} also failed for {}", dlqTopic, key, dlqFailure);
      }
    } finally {
      // Best-effort in-process notification for local listeners (GraphQL
      // subscriptions), regardless of the broker outcome.
      publishLocal(event);
    }
  }

  private void publishLocal(JobEvent event) {
    try {
      applicationEvents.publishEvent(event);
    } catch (Exception e) {
      log.error("In-process job event dispatch failed for {}", event.jobId(), e);
    }
  }

  /**
   * In-process only: notifies local listeners that the LLM returned clarifying
   * questions. The job is terminal on that path, so no stage event follows.
   */
  public void notifyNeedsClarification(Job job) {
    publishLocal(event(job, JobStatus.NEEDS_CLARIFICATION, null, job.getQueryPlanJson()));
  }

  /**
   * In-process only: notifies local listeners that the job completed (the
   * completion transition itself publishes no Kafka event).
   */
  public void notifyCompleted(Job job) {
    publishLocal(event(job, JobStatus.COMPLETED, null, null));
  }

  /** Parses a JSON payload into a {@link JobEvent}. Exposed for consumers of the Jobs module. */
  public JobEvent parse(String payload) {
    return objectMapper.readValue(payload, JobEvent.class);
  }
}
