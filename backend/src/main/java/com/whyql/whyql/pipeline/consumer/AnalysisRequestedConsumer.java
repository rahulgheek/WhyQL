package com.whyql.whyql.pipeline.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.query.QueryService;
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
  private final QueryService queryService;
  private final ObjectMapper objectMapper;

  public AnalysisRequestedConsumer(JobService jobService,
      com.whyql.whyql.jobs.service.JobEventPublisher jobEventPublisher,
      SchemaDocsService schemaDocs,
      QueryService queryService,
      ObjectMapper objectMapper) {
    this.jobService = jobService;
    this.jobEventPublisher = jobEventPublisher;
    this.schemaDocs = schemaDocs;
    this.queryService = queryService;
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
    try {
      Job job = jobService.findById(jobId);
      // Narrative answers, decided by the router: QUERY_AND_EXPLAIN reasons
      // over the executed evidence rows. Null route = legacy job from before
      // the router existed → phrase-matching fallback. A narrator failure
      // degrades to the plain result table; it must never fail a finished job.
      if (event.analysis() != null && !event.analysis().isBlank()) {
        job.setAnalysis(event.analysis());
      } else if (job.getAnalysis() == null || job.getAnalysis().isBlank()) {
        String route = event.route() != null ? event.route() : job.getRoute();
        boolean wantsNarrative =
            QueryService.ROUTE_QUERY_AND_EXPLAIN.equals(route)
                || (route == null && queryService.wantsExplanation(job.getQuestion()));
        if (wantsNarrative) {
          job.setAnalysis(buildAnalysis(job));
        }
      }
      job.markCompleted(job.getResultJson());
      jobService.save(job);
      jobEventPublisher.notifyCompleted(job);
    } catch (Exception e) {
      log.error("Completion failed for job {}", jobId, e);
      try {
        Job job = jobService.findById(jobId);
        job.markFailed("completion failed: " + e.getMessage());
        jobService.save(job);
        jobEventPublisher.notifyFailed(job);
      } catch (Exception ignored) {
        log.error("Could not even mark job {} failed: {}", jobId, e.getMessage());
      }
    }
    memorizeExemplar(jobId, jobService.findById(jobId));
  }

  /** Narrative for why-questions, absorbed; null keeps the plain table. */
  private String buildAnalysis(Job job) {
    try {
      TabularResult result = job.getResultJson() == null || job.getResultJson().isBlank()
          ? null
          : objectMapper.readValue(job.getResultJson(), TabularResult.class);
      return queryService.analyzeExplanation(job.getQuestion(), result);
    } catch (Exception e) {
      log.warn("Analysis skipped for job {}: {}", job.getId(), e.getMessage());
      return null;
    }
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
