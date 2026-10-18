package com.whyql.whyql.pipeline.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.service.JobEventPublisher;
import com.whyql.whyql.jobs.service.JobService;

/**
 * Unit test for the async entry point: creates a QUEUED job and publishes the
 * QueryRequested event immediately.
 */
class AsyncQueryOrchestratorTest {

  private JobService jobService;
  private JobEventPublisher jobEventPublisher;
  private AsyncQueryOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    jobService = mock(JobService.class);
    jobEventPublisher = mock(JobEventPublisher.class);
    orchestrator = new AsyncQueryOrchestrator(jobService, jobEventPublisher);

    Job queued = new Job();
    queued.setId(UUID.randomUUID());
    queued.setOwnerUserId("user-1");
    queued.setDataSourceId(UUID.randomUUID());
    queued.setQuestion("revenue by month");
    queued.setStatus(JobStatus.QUEUED);
    when(jobService.createJob(any(UUID.class), anyString(), anyString()))
        .thenReturn(queued);
  }

  @Test
  void startQuestionCreatesQueuedJobAndPublishes() {
    UUID dataSourceId = UUID.randomUUID();
    Job job = orchestrator.startQuestion(dataSourceId, "user-1", "revenue by month");

    org.junit.jupiter.api.Assertions.assertEquals(JobStatus.QUEUED, job.getStatus());
    verify(jobService).createJob(dataSourceId, "user-1", "revenue by month");
    verify(jobEventPublisher).publishQueryRequested(job);
  }

  @Test
  void startQuestionHandsTheCreatedJobToThePublisher() {
    Job job = orchestrator.startQuestion(UUID.randomUUID(), "user-2", "q");

    verify(jobEventPublisher).publishQueryRequested(job);
  }
}
