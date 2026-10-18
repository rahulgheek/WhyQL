package com.whyql.whyql.pipeline.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.QueryPlan;
import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;
import com.whyql.whyql.jobs.service.JobEventPublisher;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.query.QueryService;

import tools.jackson.databind.ObjectMapper;

/**
 * Stage 1 consumer: takes a queued job, generates the SQL query plan.
 *
 * Marks the job GENERATING, retrieves the schema snapshot, calls the LLM. If
 * the plan carries clarifying questions the job ends NEEDS_CLARIFICATION;
 * otherwise it moves to VALIDATING and a QueryGenerated event is published.
 */
@Component
public class QueryRequestedConsumer {

  private static final Logger log = LoggerFactory.getLogger(QueryRequestedConsumer.class);

  private final JobService jobService;
  private final QueryService queryService;
  private final JobEventPublisher jobEventPublisher;
  private final ObjectMapper objectMapper;

  public QueryRequestedConsumer(JobService jobService, QueryService queryService,
      JobEventPublisher jobEventPublisher, ObjectMapper objectMapper) {
    this.jobService = jobService;
    this.queryService = queryService;
    this.jobEventPublisher = jobEventPublisher;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = JobTopics.QUERY_REQUESTED, groupId = "whyql-query")
  public void onQueryRequested(String payload) {
    JobEvent event;
    try {
      event = objectMapper.readValue(payload, JobEvent.class);
    } catch (Exception e) {
      log.error("Unparseable query.requested payload, dropping: {}", e.getMessage());
      return;
    }

    UUID jobId = event.jobId();
    log.info("Stage 1 (generation) for job {}", jobId);
    Job job = jobService.findById(jobId);
    job.markGenerating();
    jobService.save(job);

    try {
      SchemaSnapshot snapshot = queryService.retrieveSchema(
          event.dataSourceId(), event.ownerUserId());
      QueryPlan plan = queryService.generateQuery(
          event.dataSourceId(), snapshot, event.question());
      String planJson = objectMapper.writeValueAsString(plan);
      job.setQueryPlanJson(planJson);
      jobService.save(job);

      if (plan.clarifyingQuestions() != null && !plan.clarifyingQuestions().isEmpty()) {
        job.markNeedsClarification(planJson);
        jobService.save(job);
        jobEventPublisher.notifyNeedsClarification(job);
        log.info("Job {} needs clarification; pipeline stops here", jobId);
        return;
      }

      job.markValidating();
      jobService.save(job);
      jobEventPublisher.publishQueryGenerated(job);
    } catch (Exception e) {
      log.error("Generation failed for job {}", jobId, e);
      job.markFailed("generation failed: " + e.getMessage());
      jobService.save(job);
    }
  }
}
