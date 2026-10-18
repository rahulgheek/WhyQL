package com.whyql.whyql.pipeline.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.QueryPlan;
import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.TabularResult;
import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;
import com.whyql.whyql.jobs.service.JobEventPublisher;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.query.QueryService;

import tools.jackson.databind.ObjectMapper;

/**
 * Stage 2 consumer: validates and executes the generated plan.
 *
 * Marks the job VALIDATING, re-retrieves the schema and runs the validator;
 * a validation failure fails the job. Otherwise the job moves through
 * EXECUTING, the result is stored as JSON, and a QueryExecuted event is
 * published.
 */
@Component
public class QueryGeneratedConsumer {

  private static final Logger log = LoggerFactory.getLogger(QueryGeneratedConsumer.class);

  private final JobService jobService;
  private final QueryService queryService;
  private final JobEventPublisher jobEventPublisher;
  private final ObjectMapper objectMapper;

  public QueryGeneratedConsumer(JobService jobService, QueryService queryService,
      JobEventPublisher jobEventPublisher, ObjectMapper objectMapper) {
    this.jobService = jobService;
    this.queryService = queryService;
    this.jobEventPublisher = jobEventPublisher;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = JobTopics.QUERY_GENERATED, groupId = "whyql-query")
  public void onQueryGenerated(String payload) {
    JobEvent event;
    try {
      event = objectMapper.readValue(payload, JobEvent.class);
    } catch (Exception e) {
      log.error("Unparseable query.generated payload, dropping: {}", e.getMessage());
      return;
    }

    UUID jobId = event.jobId();
    log.info("Stage 2 (validation+execution) for job {}", jobId);
    Job job = jobService.findById(jobId);

    try {
      if (event.queryPlanJson() == null || event.queryPlanJson().isBlank()) {
        throw new IllegalStateException("event carries no queryPlanJson");
      }
      job.markValidating();
      jobService.save(job);

      QueryPlan plan = objectMapper.readValue(event.queryPlanJson(), QueryPlan.class);
      SchemaSnapshot snapshot = queryService.retrieveSchema(
          event.dataSourceId(), event.ownerUserId());
      queryService.validateQuery(plan, snapshot);

      job.markExecuting();
      jobService.save(job);

      TabularResult result = queryService.executeQuery(
          event.dataSourceId(), event.ownerUserId(), plan);
      String resultJson = objectMapper.writeValueAsString(result);
      job.setResultJson(resultJson);
      jobService.save(job);

      jobEventPublisher.publishQueryExecuted(job);
    } catch (Exception e) {
      log.error("Validation/execution failed for job {}", jobId, e);
      job.markFailed("validation/execution failed: " + e.getMessage());
      jobService.save(job);
    }
  }
}
