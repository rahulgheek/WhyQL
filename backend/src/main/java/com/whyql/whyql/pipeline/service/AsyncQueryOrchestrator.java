package com.whyql.whyql.pipeline.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.service.JobEventPublisher;
import com.whyql.whyql.jobs.service.JobService;

/**
 * Async entry point for natural-language questions.
 *
 * Creates a QUEUED job, publishes the QueryRequested event (which kicks off
 * the stage pipeline), and returns the jobId immediately so no HTTP/GraphQL
 * thread ever blocks on an LLM call.
 */
@Service
public class AsyncQueryOrchestrator {

  private final JobService jobService;
  private final JobEventPublisher jobEventPublisher;

  public AsyncQueryOrchestrator(JobService jobService,
      JobEventPublisher jobEventPublisher) {
    this.jobService = jobService;
    this.jobEventPublisher = jobEventPublisher;
  }

  /**
   * Creates the job and hands it to the pipeline. Returns immediately.
   *
   * @return the queued job (id + QUEUED status)
   */
  public Job startQuestion(UUID dataSourceId, String ownerUserId, String question) {
    Job job = jobService.createJob(dataSourceId, ownerUserId, question);
    jobEventPublisher.publishQueryRequested(job);
    return job;
  }
}
