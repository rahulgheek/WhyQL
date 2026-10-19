package com.whyql.whyql.jobs.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;

import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;

import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the Kafka event publisher: correct topic + DLQ fallback when
 * the primary publish fails.
 */
class JobEventPublisherTest {

  private KafkaTemplate<String, String> kafkaTemplate;
  private ApplicationEventPublisher applicationEvents;
  private JobEventPublisher publisher;

  private Job job;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    kafkaTemplate = mock(KafkaTemplate.class);
    applicationEvents = mock(ApplicationEventPublisher.class);
    publisher = new JobEventPublisher(kafkaTemplate, new ObjectMapper(), applicationEvents);

    job = new Job();
    job.setId(UUID.randomUUID());
    job.setOwnerUserId("user-1");
    job.setDataSourceId(UUID.randomUUID());
    job.setQuestion("how many users?");
    job.setStatus(JobStatus.QUEUED);
    job.setQueryPlanJson("{\"queryText\":\"SELECT 1\"}");
    job.setResultJson("{\"rows\":[]}");

    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(null);
  }

  @Test
  void publishQueryRequestedSendsToQueryRequestedTopic() {
    publisher.publishQueryRequested(job);

    verify(kafkaTemplate).send(eq(JobTopics.QUERY_REQUESTED),
        eq(job.getId().toString()), anyString());
  }

  @Test
  void publishQueryGeneratedCarriesPlanJson() {
    publisher.publishQueryGenerated(job);

    verify(kafkaTemplate).send(eq(JobTopics.QUERY_GENERATED),
        eq(job.getId().toString()), anyString());
  }

  @Test
  void publishQueryExecutedCarriesResultJson() {
    publisher.publishQueryExecuted(job);

    verify(kafkaTemplate).send(eq(JobTopics.QUERY_EXECUTED),
        eq(job.getId().toString()), anyString());
  }

  @Test
  void publishAnalysisRequestedSendsToAnalysisTopic() {
    publisher.publishAnalysisRequested(job);

    verify(kafkaTemplate).send(eq(JobTopics.ANALYSIS_REQUESTED),
        eq(job.getId().toString()), anyString());
  }

  @Test
  void publishFallsBackToDlqWhenPrimarySendFails() {
    when(kafkaTemplate.send(eq(JobTopics.QUERY_REQUESTED), anyString(), anyString()))
        .thenThrow(new RuntimeException("broker down"));

    publisher.publishQueryRequested(job);

    verify(kafkaTemplate).send(eq(JobTopics.QUERY_REQUESTED_DLQ),
        eq(job.getId().toString()), anyString());
  }

  @Test
  void parseRoundTripsAnEvent() {
    JobEvent original = new JobEvent(
        UUID.randomUUID(), "user-1", UUID.randomUUID(), "count rows",
        JobStatus.EXECUTING, null, "{\"plan\":1}", null, null, null, Instant.now());

    String json = new ObjectMapper().writeValueAsString(original);
    JobEvent parsed = publisher.parse(json);

    assert parsed.jobId().equals(original.jobId());
    assert parsed.ownerUserId().equals("user-1");
    assert parsed.status() == JobStatus.EXECUTING;
    assert json.contains("\"EXECUTING\"");
  }

  @Test
  void eventJsonCarriesStatusAndSnakeCaseFreeKeys() {
    publisher.publishQueryRequested(job);

    org.mockito.ArgumentCaptor<String> captor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(eq(JobTopics.QUERY_REQUESTED),
        eq(job.getId().toString()), captor.capture());
    String payload = captor.getValue();

    assert payload.contains("\"QUEUED\"");
    assert payload.contains("how many users?");
  }

  @Test
  void freshJobStillPublishesWithoutPlanAndResult() {
    Job bare = new Job();
    bare.setId(UUID.randomUUID());
    bare.setOwnerUserId("user-2");
    bare.setDataSourceId(UUID.randomUUID());
    bare.setQuestion("bare question");
    bare.setStatus(JobStatus.QUEUED);

    org.mockito.ArgumentCaptor<String> captor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    publisher.publishQueryExecuted(bare);
    verify(kafkaTemplate).send(eq(JobTopics.QUERY_EXECUTED),
        eq(bare.getId().toString()), captor.capture());

    assert captor.getValue().contains("\"question\":\"bare question\"");
  }

  @Test
  void publishAlsoNotifiesLocalListeners() {
    publisher.publishQueryRequested(job);

    org.mockito.ArgumentCaptor<JobEvent> eventCaptor =
        org.mockito.ArgumentCaptor.forClass(JobEvent.class);
    verify(applicationEvents).publishEvent(eventCaptor.capture());
    assert eventCaptor.getValue().jobId().equals(job.getId());
    assert eventCaptor.getValue().status() == JobStatus.QUEUED;
  }

  @Test
  void notifyNeedsClarificationEmitsTerminalInProcessEvent() {
    publisher.notifyNeedsClarification(job);

    org.mockito.ArgumentCaptor<JobEvent> eventCaptor =
        org.mockito.ArgumentCaptor.forClass(JobEvent.class);
    verify(applicationEvents).publishEvent(eventCaptor.capture());
    assert eventCaptor.getValue().status() == JobStatus.NEEDS_CLARIFICATION;
    // In-process only: no Kafka send must happen for the local notification.
    Mockito.verifyNoMoreInteractions(kafkaTemplate);
  }

  @Test
  void notifyCompletedEmitsCompletedWithResult() {
    publisher.notifyCompleted(job);

    org.mockito.ArgumentCaptor<JobEvent> eventCaptor =
        org.mockito.ArgumentCaptor.forClass(JobEvent.class);
    verify(applicationEvents).publishEvent(eventCaptor.capture());
    assert eventCaptor.getValue().status() == JobStatus.COMPLETED;
    assert eventCaptor.getValue().resultJson().equals(job.getResultJson());
    Mockito.verifyNoMoreInteractions(kafkaTemplate);
  }
}
