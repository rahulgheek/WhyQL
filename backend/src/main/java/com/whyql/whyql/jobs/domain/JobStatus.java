package com.whyql.whyql.jobs.domain;

/**
 * Job lifecycle: QUEUED on creation, then one in-flight stage at a time
 * (GENERATING -> VALIDATING -> EXECUTING -> ANALYZING), and one terminal state
 * (COMPLETED, NEEDS_CLARIFICATION, FAILED).
 */
public enum JobStatus {
  QUEUED,
  GENERATING,
  VALIDATING,
  EXECUTING,
  ANALYZING,
  COMPLETED,
  NEEDS_CLARIFICATION,
  FAILED
}
