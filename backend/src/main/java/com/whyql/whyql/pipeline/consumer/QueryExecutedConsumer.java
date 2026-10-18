package com.whyql.whyql.pipeline.consumer;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;
import com.whyql.whyql.jobs.service.JobEventPublisher;
import com.whyql.whyql.jobs.service.JobService;

import tools.jackson.databind.ObjectMapper;

/**
 * Stage 3 consumer: hands the executed result to the analysis stage.
 *
 * Marks the job ANALYZING and publishes an AnalysisRequested event. Kept as a
 * separate topic so an LLM-based narrative analysis can be added later
 * without re-running validation/execution.
 */
@Component
public class QueryExecutedConsumer {

  private static final Logger log = LoggerFactory.getLogger(QueryExecutedConsumer.class);

  private final JobService jobService;
  private final JobEventPublisher jobEventPublisher;
  private final ObjectMapper objectMapper;

  public QueryExecutedConsumer(JobService jobService,
      JobEventPublisher jobEventPublisher, ObjectMapper objectMapper) {
    this.jobService = jobService;
    this.jobEventPublisher = jobEventPublisher;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = JobTopics.QUERY_EXECUTED, groupId = "whyql-query")
  public void onQueryExecuted(String payload) {
    JobEvent event;
    try {
      event = objectMapper.readValue(payload, JobEvent.class);
    } catch (Exception e) {
      log.error("Unparseable query.executed payload, dropping: {}", e.getMessage());
      return;
    }

    UUID jobId = event.jobId();
    log.info("Stage 3 (analysis handoff) for job {}", jobId);
    Job job = jobService.findById(jobId);
    job.markAnalyzing();
    jobService.save(job);

    jobEventPublisher.publishAnalysisRequested(job);
  }
}
