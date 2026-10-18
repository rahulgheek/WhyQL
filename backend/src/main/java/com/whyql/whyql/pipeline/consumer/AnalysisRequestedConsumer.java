package com.whyql.whyql.pipeline.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.QueryPlan;
import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.rag.service.SchemaDocsService;

import tools.jackson.databind.ObjectMapper;

/**
 * Final stage consumer: completes the job.
 *
 * The job arrives ANALYZING with the result JSON already stored; this
 * consumer marks it COMPLETED. (LLM narrative analysis can be inserted here
 * later without changing the event contract.)
 *
 * On completion the validated question→SQL pair is memorized into the RAG
 * store so future similar questions receive it as a few-shot exemplar.
 */
@Component
public class AnalysisRequestedConsumer {

  private static final Logger log = LoggerFactory.getLogger(AnalysisRequestedConsumer.class);

  private final JobService jobService;
  private final com.whyql.whyql.jobs.service.JobEventPublisher jobEventPublisher;
  private final SchemaDocsService schemaDocs;
  private final ObjectMapper objectMapper;

  public AnalysisRequestedConsumer(JobService jobService,
      com.whyql.whyql.jobs.service.JobEventPublisher jobEventPublisher,
      SchemaDocsService schemaDocs,
      ObjectMapper objectMapper) {
    this.jobService = jobService;
    this.jobEventPublisher = jobEventPublisher;
    this.schemaDocs = schemaDocs;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = JobTopics.ANALYSIS_REQUESTED, groupId = "whyql-query")
  public void onAnalysisRequested(String payload) {
    JobEvent event;
    try {
      event = objectMapper.readValue(payload, JobEvent.class);
    } catch (Exception e) {
      log.error("Unparseable analysis.requested payload, dropping: {}", e.getMessage());
      return;
    }

    UUID jobId = event.jobId();
    log.info("Stage 4 (complete) for job {}", jobId);
    Job job = jobService.findById(jobId);
    job.markCompleted(job.getResultJson());
    jobService.save(job);
    jobEventPublisher.notifyCompleted(job);
    memorizeExemplar(jobId, job);
  }

  /**
   * Adds the completed job to the RAG exemplar memory. Memory failure must
   * never fail the completed job itself, so any problem is absorbed here.
   */
  private void memorizeExemplar(UUID jobId, Job job) {
    try {
      if (job.getQueryPlanJson() == null || job.getQueryPlanJson().isBlank()) {
        return;
      }
      QueryPlan plan = objectMapper.readValue(job.getQueryPlanJson(), QueryPlan.class);
      if (plan.queryText() != null && !plan.queryText().isBlank()) {
        schemaDocs.memorize(job.getDataSourceId(), job.getQuestion(), plan.queryText());
        log.info("Job {} memorized as exemplar for data source {}", jobId,
            job.getDataSourceId());
      }
    } catch (Exception e) {
      log.warn("Exemplar memorization failed for job {}: {}", jobId, e.getMessage());
    }
  }
}
