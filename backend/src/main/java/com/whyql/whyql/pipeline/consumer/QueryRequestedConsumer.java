package com.whyql.whyql.pipeline.consumer;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.whyql.whyql.chat.service.ChatService;
import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;
import com.whyql.whyql.jobs.service.JobEventPublisher;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.query.QueryService;
import com.whyql.whyql.query.QueryService;

import tools.jackson.databind.ObjectMapper;

/**
 * Stage 1 consumer: takes a queued job, generates the SQL query plan.
 *
 * Marks the job GENERATING, retrieves the schema snapshot, calls the LLM. If
 * the plan carries clarifying questions the job ends NEEDS_CLARIFICATION;
 * otherwise it moves to VALIDATING and a QueryGenerated event is published.
 */
@Component
public class QueryRequestedConsumer {

  private static final Logger log = LoggerFactory.getLogger(QueryRequestedConsumer.class);

  private final JobService jobService;
  private final QueryService queryService;
  private final JobEventPublisher jobEventPublisher;
  private final ObjectMapper objectMapper;
  private final ChatService chatService;

  public QueryRequestedConsumer(JobService jobService, QueryService queryService,
      JobEventPublisher jobEventPublisher, ObjectMapper objectMapper,
      ChatService chatService) {
    this.jobService = jobService;
    this.queryService = queryService;
    this.jobEventPublisher = jobEventPublisher;
    this.objectMapper = objectMapper;
    this.chatService = chatService;
  }

  @KafkaListener(topics = JobTopics.QUERY_REQUESTED, groupId = "whyql-query")
  public void onQueryRequested(String payload) {
    JobEvent event;
    try {
      event = objectMapper.readValue(payload, JobEvent.class);
    } catch (Exception e) {
      log.error("Unparseable query.requested payload, dropping: {}", e.getMessage());
      return;
    }

    UUID jobId = event.jobId();
    log.info("Stage 1 (generation) for job {}", jobId);
    Job job = jobService.findById(jobId);
    if (job.getChatSessionId() != null) {
      chatService.recordUserMessage(job.getChatSessionId(), job.getOwnerUserId(),
          job.getQuestion(), jobId);
    }
    job.markGenerating();
    jobService.save(job);

    try {
      // Fast path: questions about the schema itself ("how many tables are
      // there") answer instantly from the snapshot — no LLM, no SQL, never
      // clarifies. A datasource that fails introspection falls through to the
      // normal pipeline so its error is reported the usual way.
      Optional<TabularResult> metaAnswer = queryService.answerSchemaQuestion(
          event.dataSourceId(), event.ownerUserId(), event.question());
      if (metaAnswer.isPresent()) {
        job.markCompleted(objectMapper.writeValueAsString(metaAnswer.get()));
        jobService.save(job);
        if (job.getChatSessionId() != null) {
          chatService.recordAssistantMessage(job.getChatSessionId(), job.getOwnerUserId(),
              "Answered from the schema snapshot.", jobId);
        }
        jobEventPublisher.notifyCompleted(job);
        log.info("Job {} answered from the schema snapshot (no LLM needed)", jobId);
        return;
      }

      // LLM router: the question decides its own path — SQL answer, SQL
      // evidence + reasoning, or general knowledge (no database at all).
      // A null/blank route means the router was unavailable: DATA_QUERY is
      // the safe degradation, identical to the pre-router pipeline.
      String route = queryService.routeQuestion(event.question());
      if (route == null || route.isBlank()) {
        route = QueryService.ROUTE_DATA_QUERY;
      }
      job.setRoute(route);
      jobService.save(job);

      if (QueryService.ROUTE_GENERAL.equals(route)) {
        String answer = queryService.answerGeneral(event.question());
        job.setAnalysis(answer);
        job.markCompleted(null);
        jobService.save(job);
        if (job.getChatSessionId() != null) {
          chatService.recordAssistantMessage(job.getChatSessionId(), job.getOwnerUserId(),
              answer, jobId);
        }
        jobEventPublisher.notifyCompleted(job);
        log.info("Job {} answered as general knowledge (no database query)", jobId);
        return;
      }

      SchemaSnapshot snapshot = queryService.retrieveSchema(
          event.dataSourceId(), event.ownerUserId());
      QueryPlan plan = queryService.generateQuery(
          event.dataSourceId(), event.ownerUserId(), snapshot, event.question());
      String planJson = objectMapper.writeValueAsString(plan);
      job.setQueryPlanJson(planJson);
      jobService.save(job);

      if (plan.clarifyingQuestions() != null && !plan.clarifyingQuestions().isEmpty()) {
        job.markNeedsClarification(planJson);
        jobService.save(job);
        if (job.getChatSessionId() != null) {
          chatService.recordAssistantMessage(job.getChatSessionId(), job.getOwnerUserId(),
              null, jobId);
        }
        jobEventPublisher.notifyNeedsClarification(job);
        log.info("Job {} needs clarification; pipeline stops here", jobId);
        return;
      }

      job.markValidating();
      jobService.save(job);
      jobEventPublisher.publishQueryGenerated(job);
    } catch (Exception e) {
      log.error("Generation failed for job {}", jobId, e);
      job.markFailed("generation failed: " + e.getMessage());
      jobService.save(job);
      if (job.getChatSessionId() != null) {
        chatService.recordAssistantMessage(job.getChatSessionId(), job.getOwnerUserId(),
            null, jobId);
      }
      jobEventPublisher.notifyFailed(job);
    }
  }
}
