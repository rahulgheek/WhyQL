package com.whyql.whyql.pipeline.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.whyql.whyql.datasource.domain.Engine;
import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.service.JobEventPublisher;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.query.QueryService;

import tools.jackson.databind.ObjectMapper;

/**
 * Pure Mockito tests for the asynchronous stage pipeline: job state
 * transitions and event hand-offs between the four consumers.
 */
class PipelineConsumersTest {

  private JobService jobService;
  private QueryService queryService;
  private com.whyql.whyql.rag.service.SchemaDocsService schemaDocs;
  private JobEventPublisher jobEventPublisher;
  private ObjectMapper objectMapper;

  private Job job;

  @BeforeEach
  void setUp() {
    jobService = mock(JobService.class);
    queryService = mock(QueryService.class);
    schemaDocs = mock(com.whyql.whyql.rag.service.SchemaDocsService.class);
    jobEventPublisher = mock(JobEventPublisher.class);
    objectMapper = new ObjectMapper();

    job = new Job();
    job.setId(UUID.randomUUID());
    job.setOwnerUserId("user-1");
    job.setDataSourceId(UUID.randomUUID());
    job.setQuestion("top 5 customers");
    job.setStatus(JobStatus.QUEUED);

    when(jobService.findById(job.getId())).thenReturn(job);
    when(jobService.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private String payload() {
    return objectMapper.writeValueAsString(new JobEvent(
        job.getId(), job.getOwnerUserId(), job.getDataSourceId(),
        job.getQuestion(), job.getStatus(), job.getErrorMessage(),
        job.getQueryPlanJson(), job.getResultJson(), null, null, Instant.now()));
  }

  private SchemaSnapshot snapshot() {
    return new SchemaSnapshot("demo", List.of(), Instant.now());
  }

  @Test
  void queryRequestedConsumerGeneratesPlanAndHandsOff() {
    QueryPlan plan = new QueryPlan(
        Engine.POSTGRES, "SELECT name FROM customers LIMIT 5", "simple top-5",
        List.of(), List.of());

    when(queryService.retrieveSchema(any(UUID.class), anyString())).thenReturn(snapshot());
    when(queryService.generateQuery(any(UUID.class), anyString(), any(SchemaSnapshot.class), anyString()))
        .thenReturn(plan);

    new QueryRequestedConsumer(jobService, queryService, jobEventPublisher,
        objectMapper).onQueryRequested(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.VALIDATING, job.getStatus());
    verify(jobEventPublisher).publishQueryGenerated(job);
  }

  @Test
  void queryRequestedConsumerStopsOnClarifyingQuestions() {
    QueryPlan plan = new QueryPlan(
        Engine.POSTGRES, "", "", List.of(), List.of("Which date range?"));

    when(queryService.retrieveSchema(any(UUID.class), anyString())).thenReturn(snapshot());
    when(queryService.generateQuery(any(UUID.class), anyString(), any(SchemaSnapshot.class), anyString()))
        .thenReturn(plan);

    new QueryRequestedConsumer(jobService, queryService, jobEventPublisher,
        objectMapper).onQueryRequested(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.NEEDS_CLARIFICATION, job.getStatus());
    org.junit.jupiter.api.Assertions.assertTrue(job.isTerminal());
    verify(jobEventPublisher, never()).publishQueryGenerated(Mockito.any(Job.class));
  }

  @Test
  void queryRequestedConsumerFailsJobOnLlmError() {
    when(queryService.retrieveSchema(any(UUID.class), anyString()))
        .thenThrow(new RuntimeException("connection refused"));

    new QueryRequestedConsumer(jobService, queryService, jobEventPublisher,
        objectMapper).onQueryRequested(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.FAILED, job.getStatus());
    org.junit.jupiter.api.Assertions.assertTrue(
        job.getErrorMessage().contains("generation failed"));
    verify(jobEventPublisher, never()).publishQueryGenerated(Mockito.any(Job.class));
  }

  @Test
  void queryGeneratedConsumerValidatesExecutesAndHandsOff() {
    job.setQueryPlanJson(objectMapper.writeValueAsString(new QueryPlan(
        Engine.POSTGRES, "SELECT 1", "", List.of(), List.of())));

    when(queryService.retrieveSchema(any(UUID.class), anyString())).thenReturn(snapshot());
    TabularResult result = new TabularResult(
        List.of(new TabularResult.ResultColumn("one", "int4")),
        List.of(List.of(1)), false);
    when(queryService.executeQuery(any(UUID.class), anyString(),
        any(QueryPlan.class))).thenReturn(result);

    new QueryGeneratedConsumer(jobService, queryService, jobEventPublisher,
        objectMapper).onQueryGenerated(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.EXECUTING, job.getStatus());
    verify(jobEventPublisher).publishQueryExecuted(job);
  }

  @Test
  void queryGeneratedConsumerFailsOnValidationError() {
    job.setQueryPlanJson(objectMapper.writeValueAsString(new QueryPlan(
        Engine.POSTGRES, "DELETE FROM x", "", List.of(), List.of())));

    when(queryService.retrieveSchema(any(UUID.class), anyString())).thenReturn(snapshot());
    when(queryService.validateQuery(any(QueryPlan.class), any(SchemaSnapshot.class)))
        .thenThrow(new IllegalStateException("Validation failed: mutation"));

    new QueryGeneratedConsumer(jobService, queryService, jobEventPublisher,
        objectMapper).onQueryGenerated(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.FAILED, job.getStatus());
    verify(jobEventPublisher, never()).publishQueryExecuted(Mockito.any(Job.class));
  }

  @Test
  void queryGeneratedConsumerFailsWhenPlanJsonMissing() {
    new QueryGeneratedConsumer(jobService, queryService, jobEventPublisher,
        objectMapper).onQueryGenerated(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.FAILED, job.getStatus());
    verify(jobEventPublisher, never()).publishQueryExecuted(Mockito.any(Job.class));
  }

  @Test
  void queryExecutedConsumerMarksAnalyzingAndPublishes() {
    new QueryExecutedConsumer(jobService, jobEventPublisher, objectMapper)
        .onQueryExecuted(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.ANALYZING, job.getStatus());
    verify(jobEventPublisher).publishAnalysisRequested(job);
  }

  @Test
  void analysisRequestedConsumerCompletesTheJob() {
    job.setStatus(JobStatus.ANALYZING);
    job.setResultJson("[]");

    JobEventPublisher publisher = mock(JobEventPublisher.class);
    new AnalysisRequestedConsumer(jobService, publisher, schemaDocs, queryService, objectMapper)
        .onAnalysisRequested(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.COMPLETED, job.getStatus());
    org.junit.jupiter.api.Assertions.assertTrue(job.isTerminal());
    verify(publisher).notifyCompleted(job);
  }

  @Test
  void unparseablePayloadIsDroppedGracefully() {
    new QueryRequestedConsumer(jobService, queryService, jobEventPublisher,
        objectMapper).onQueryRequested("not json");
    new QueryGeneratedConsumer(jobService, queryService, jobEventPublisher,
        objectMapper).onQueryGenerated("not json");
    new QueryExecutedConsumer(jobService, jobEventPublisher, objectMapper)
        .onQueryExecuted("not json");
    new AnalysisRequestedConsumer(jobService, jobEventPublisher, schemaDocs, queryService, objectMapper)
        .onAnalysisRequested("not json");

    verify(jobService, never()).findById(any(UUID.class));
  }

  @Test
  void analysisRequestedConsumerMemorizesCompletedQuestionAsExemplar() throws Exception {
    job.setStatus(JobStatus.ANALYZING);
    job.setResultJson("[]");
    QueryPlan plan = new QueryPlan(
        Engine.POSTGRES, "SELECT count(*) FROM customers", "", List.of(), List.of());
    job.setQueryPlanJson(objectMapper.writeValueAsString(plan));

    new AnalysisRequestedConsumer(jobService, jobEventPublisher, schemaDocs, queryService, objectMapper)
        .onAnalysisRequested(payload());

    verify(schemaDocs).memorize(job.getDataSourceId(), job.getQuestion(), plan.queryText());
  }

  @Test
  void analysisRequestedConsumerSkipsMemoryWithoutPlanSql() {
    job.setStatus(JobStatus.ANALYZING);
    job.setResultJson("[]");
    // No queryPlanJson set → nothing memorized, job still completes.

    new AnalysisRequestedConsumer(jobService, jobEventPublisher, schemaDocs, queryService, objectMapper)
        .onAnalysisRequested(payload());

    org.junit.jupiter.api.Assertions.assertEquals(
        JobStatus.COMPLETED, job.getStatus());
    verify(schemaDocs, never()).memorize(any(UUID.class), anyString(), anyString());
  }
}
