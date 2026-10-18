package com.whyql.whyql.api.job;

import org.springframework.stereotype.Component;

import com.whyql.whyql.api.model.Job;
import com.whyql.whyql.api.model.QueryResult;
import com.whyql.whyql.datasource.TabularResult;
import com.whyql.whyql.jobs.domain.JobStatus;

import tools.jackson.databind.ObjectMapper;

/**
 * Maps job entities/events to the GraphQL {@code Job} shape, including the
 * progress curve and result-JSON parsing.
 */
@Component
public class JobMapper {

  private final ObjectMapper objectMapper;

  public JobMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Job toGql(com.whyql.whyql.jobs.domain.Job entity) {
    return new Job(
        entity.getId(),
        entity.getStatus().name(),
        progress(entity.getStatus()),
        entity.getOwnerUserId(),
        entity.getDataSourceId(),
        entity.getQuestion(),
        entity.getErrorMessage(),
        toResult(entity.getResultJson()),
        entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
        entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
  }

  public Job fromEvent(com.whyql.whyql.jobs.event.JobEvent event) {
    return new Job(
        event.jobId(),
        event.status().name(),
        progress(event.status()),
        event.ownerUserId(),
        event.dataSourceId(),
        event.question(),
        event.errorMessage(),
        toResult(event.resultJson()),
        null,
        null);
  }

  /**
   * Progress curve shown to clients while stages stream by. 5% on queue-up,
   * stage-scaled across the four pipeline stages, 100% on any terminal state.
   */
  public static int progress(JobStatus status) {
    return switch (status) {
      case QUEUED -> 5;
      case GENERATING -> 25;
      case VALIDATING -> 50;
      case EXECUTING -> 75;
      case ANALYZING -> 90;
      case COMPLETED, NEEDS_CLARIFICATION, FAILED -> 100;
    };
  }

  private QueryResult toResult(String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      TabularResult parsed = objectMapper.readValue(resultJson, TabularResult.class);
      return QueryResult.from(parsed);
    } catch (Exception e) {
      // A malformed stored result shouldn't 500 the whole job view; surface
      // nothing rather than failing the query.
      return null;
    }
  }
}
