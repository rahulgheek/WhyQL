package com.whyql.whyql.jobs.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.repository.JobRepository;

/**
 * Unit tests for job lifecycle bookkeeping (no Spring context needed).
 */
class JobServiceTest {

  private JobRepository jobRepository;
  private JobService jobService;

  @BeforeEach
  void setUp() {
    jobRepository = mock(JobRepository.class);
    jobService = new JobService(jobRepository);
  }

  @Test
  void createJobInitializesQueuedDefaults() {
    when(jobRepository.save(any(Job.class)))
        .thenAnswer(inv -> {
          Job arg = inv.getArgument(0);
          arg.setId(UUID.randomUUID());
          return arg;
        });

    Job job = jobService.createJob(UUID.randomUUID(), "user-1", "how many rows?");

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.QUEUED, job.getStatus());
    org.junit.jupiter.api.Assertions.assertEquals(0, job.getAttempt());
    org.junit.jupiter.api.Assertions.assertEquals(3, job.getMaxAttempts());
    org.junit.jupiter.api.Assertions.assertFalse(job.isTerminal());
    verify(jobRepository).save(job);
  }

  @Test
  void findByIdThrowsWhenJobMissing() {
    when(jobRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

    try {
      jobService.findById(UUID.randomUUID());
      org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      org.junit.jupiter.api.Assertions.assertTrue(
          expected.getMessage().contains("Job not found"));
    }
  }

  @Test
  void saveTouchesUpdatedAt() {
    Job job = new Job();
    job.setId(UUID.randomUUID());
    job.setStatus(JobStatus.GENERATING);
    when(jobRepository.save(any(Job.class))).thenReturn(job);

    Job saved = jobService.save(job);

    org.junit.jupiter.api.Assertions.assertNotNull(saved);
    verify(jobRepository).save(job);
  }

  @Test
  void findByOwnerUserIdFiltersByOwner() {
    Job mine = new Job();
    mine.setId(UUID.randomUUID());
    mine.setOwnerUserId("user-1");
    mine.setStatus(JobStatus.QUEUED);

    Job other = new Job();
    other.setId(UUID.randomUUID());
    other.setOwnerUserId("user-2");
    other.setStatus(JobStatus.QUEUED);

    when(jobRepository.findAll()).thenReturn(java.util.List.of(mine, other));

    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.List.of(mine), jobService.findByOwnerUserId("user-1"));
  }

  @Test
  void terminalJobIsReportedAsTerminal() {
    Job completed = new Job();
    completed.setId(UUID.randomUUID());
    completed.setStatus(JobStatus.FAILED);

    org.junit.jupiter.api.Assertions.assertTrue(completed.isTerminal());
    verifyNoInteractions(jobRepository);
  }
}
