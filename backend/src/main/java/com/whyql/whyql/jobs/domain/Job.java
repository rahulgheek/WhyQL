package com.whyql.whyql.jobs.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Job {

  @Id
  @GeneratedValue
  private UUID id;

  private String ownerUserId;

  private UUID dataSourceId;

  private String question;

  @Enumerated(EnumType.STRING)
  private JobStatus status = JobStatus.QUEUED;

  private Integer attempt = 0;

  private Integer maxAttempts = 3;

  private String errorMessage;

  private String queryPlanJson;

  private String resultJson;

  /** Narrative explanation for why-questions; null for plain data answers. */
  private String analysis;

  /** Router decision: DATA_QUERY / QUERY_AND_EXPLAIN / GENERAL. */
  private String route;

  /** Conversation this question belongs to; null for sessionless/legacy asks. */
  private UUID chatSessionId;

  private Instant createdAt = Instant.now();

  private Instant updatedAt = Instant.now();

  private Instant completedAt;

  private Instant startedAt;

  public void markGenerating() {
    this.status = JobStatus.GENERATING;
    this.startedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void markValidating() {
    this.status = JobStatus.VALIDATING;
    this.updatedAt = Instant.now();
  }

  public void markExecuting() {
    this.status = JobStatus.EXECUTING;
    this.updatedAt = Instant.now();
  }

  public void markAnalyzing() {
    this.status = JobStatus.ANALYZING;
    this.updatedAt = Instant.now();
  }

  public void markCompleted(String resultJson) {
    this.status = JobStatus.COMPLETED;
    this.resultJson = resultJson;
    this.completedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void markNeedsClarification(String queryPlanJson) {
    this.status = JobStatus.NEEDS_CLARIFICATION;
    this.queryPlanJson = queryPlanJson;
    this.completedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void markFailed(String errorMessage) {
    this.status = JobStatus.FAILED;
    this.errorMessage = errorMessage;
    this.completedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void incrementAttempt() {
    this.attempt = (this.attempt == null) ? 1 : this.attempt + 1;
    this.updatedAt = Instant.now();
  }

  public boolean isTerminal() {
    return status == JobStatus.COMPLETED
        || status == JobStatus.FAILED
        || status == JobStatus.NEEDS_CLARIFICATION;
  }
}
