package com.whyql.whyql.api.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.api.model.Job;
import com.whyql.whyql.api.model.QueryResult;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.jobs.domain.JobStatus;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for job -> GraphQL mapping: the progress curve and the
 * result-JSON parsing behind {@code Job.result}.
 */
class JobMapperTest {

  private final JobMapper mapper = new JobMapper(new ObjectMapper());

  @Test
  void progressCurveFollowsTheStages() {
    assertThat(JobMapper.progress(JobStatus.QUEUED)).isEqualTo(5);
    assertThat(JobMapper.progress(JobStatus.GENERATING)).isEqualTo(25);
    assertThat(JobMapper.progress(JobStatus.VALIDATING)).isEqualTo(50);
    assertThat(JobMapper.progress(JobStatus.EXECUTING)).isEqualTo(75);
    assertThat(JobMapper.progress(JobStatus.ANALYZING)).isEqualTo(90);
    assertThat(JobMapper.progress(JobStatus.COMPLETED)).isEqualTo(100);
    assertThat(JobMapper.progress(JobStatus.NEEDS_CLARIFICATION)).isEqualTo(100);
    assertThat(JobMapper.progress(JobStatus.FAILED)).isEqualTo(100);
  }

  @Test
  void toGqlMapsEntityFieldsAndParsesResultJson() {
    com.whyql.whyql.jobs.domain.Job entity = new com.whyql.whyql.jobs.domain.Job();
    entity.setId(UUID.randomUUID());
    entity.setOwnerUserId("user-1");
    entity.setDataSourceId(UUID.randomUUID());
    entity.setQuestion("revenue by country");
    entity.setStatus(JobStatus.COMPLETED);
    entity.setErrorMessage(null);
    entity.setResultJson(objectJson());
    entity.setCreatedAt(java.time.Instant.parse("2026-10-18T10:00:00Z"));
    entity.setUpdatedAt(java.time.Instant.parse("2026-10-18T10:05:00Z"));

    Job view = mapper.toGql(entity);

    assertThat(view.id()).isEqualTo(entity.getId());
    assertThat(view.status()).isEqualTo("COMPLETED");
    assertThat(view.progress()).isEqualTo(100);
    assertThat(view.ownerUserId()).isEqualTo("user-1");
    assertThat(view.question()).isEqualTo("revenue by country");
    assertThat(view.createdAt()).isEqualTo("2026-10-18T10:00:00Z");
    assertThat(view.updatedAt()).isEqualTo("2026-10-18T10:05:00Z");

    assertThat(view.result()).isNotNull();
    assertThat(view.result().columns()).hasSize(1);
    assertThat(view.result().columns().get(0).name()).isEqualTo("country");
    assertThat(view.result().columns().get(0).typeName()).isEqualTo("text");
    assertThat(view.result().rows()).hasSize(2);
    assertThat(view.result().truncated()).isFalse();
  }

  @Test
  void toGqlYieldsNullResultWhenJsonMissingOrMalformed() {
    com.whyql.whyql.jobs.domain.Job queued = new com.whyql.whyql.jobs.domain.Job();
    queued.setId(UUID.randomUUID());
    queued.setOwnerUserId("u");
    queued.setDataSourceId(UUID.randomUUID());
    queued.setQuestion("q");
    queued.setStatus(JobStatus.QUEUED);

    assertThat(mapper.toGql(queued).result()).isNull();

    queued.setStatus(JobStatus.FAILED);
    queued.setErrorMessage("generation failed: boom");
    queued.setResultJson("not json {");

    Job failed = mapper.toGql(queued);
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.errorMessage()).contains("boom");
    assertThat(failed.result()).isNull();
  }

  @Test
  void fromEventMapsStageEventWithoutTimestamps() {
    UUID jobId = UUID.randomUUID();
    var event = new com.whyql.whyql.jobs.event.JobEvent(
        jobId, "user-9", UUID.randomUUID(), "top products",
        JobStatus.EXECUTING, null, "{\"queryText\":\"SELECT 1\"}",
        objectJson(), null, null, java.time.Instant.now());

    Job view = mapper.fromEvent(event);

    assertThat(view.id()).isEqualTo(jobId);
    assertThat(view.status()).isEqualTo("EXECUTING");
    assertThat(view.progress()).isEqualTo(75);
    assertThat(view.ownerUserId()).isEqualTo("user-9");
    assertThat(view.result()).isNotNull();
    assertThat(view.createdAt()).isNull();
    assertThat(view.updatedAt()).isNull();
  }

  private String objectJson() {
    try {
      return new ObjectMapper().writeValueAsString(new TabularResult(
          List.of(new TabularResult.ResultColumn("country", "text")),
          List.of(List.of("US"), List.of("DE")),
          false));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
