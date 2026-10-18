package com.whyql.whyql.jobs.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.repository.JobRepository;

/**
 * Job lifecycle bookkeeping. Exposed (Modulith named interface) because the
 * Pipeline module drives job state transitions through this service.
 */
@Service
public class JobService {

  private final JobRepository jobRepository;

  public JobService(JobRepository jobRepository) {
    this.jobRepository = jobRepository;
  }

  @Transactional
  public Job createJob(UUID dataSourceId, String ownerUserId, String question) {
    Job job = new Job();
    // NOTE: id must be left null so Spring Data save() runs persist (insert).
    // Setting it manually makes save() merge and fail with StaleObjectStateException.
    job.setDataSourceId(dataSourceId);
    job.setOwnerUserId(ownerUserId);
    job.setQuestion(question);
    job.setStatus(JobStatus.QUEUED);
    job.setAttempt(0);
    job.setMaxAttempts(3);
    job.setCreatedAt(java.time.Instant.now());
    job.setUpdatedAt(java.time.Instant.now());
    return jobRepository.save(job);
  }

  public Job findById(UUID jobId) {
    return jobRepository.findById(jobId)
        .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
  }

  @Transactional
  public Job save(Job job) {
    job.setUpdatedAt(java.time.Instant.now());
    return jobRepository.save(job);
  }

  public List<Job> findByOwnerUserId(String ownerUserId) {
    return jobRepository.findAll().stream()
        .filter(j -> ownerUserId.equals(j.getOwnerUserId()))
        .toList();
  }
}
