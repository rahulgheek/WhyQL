package com.whyql.whyql.pipeline.consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.ResultEnvelope;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.event.JobEvent;
import com.whyql.whyql.jobs.event.JobTopics;
import com.whyql.whyql.jobs.service.JobEventPublisher;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.query.QueryService;
import com.whyql.whyql.query.ResultSizePolicy;

import tools.jackson.databind.ObjectMapper;

/**
 * Stage 2 consumer: validates and executes the generated plan.
 *
 * Marks the job VALIDATING, re-retrieves the schema and runs the validator;
 * a validation failure fails the job. Otherwise the job moves through
 * EXECUTING, the result sections (size-policy enforced) are stored as a
 * {@link ResultEnvelope} JSON, and a QueryExecuted event is published.
 */
@Component
public class QueryGeneratedConsumer {

  private static final Logger log = LoggerFactory.getLogger(QueryGeneratedConsumer.class);

  private final JobService jobService;
  private final QueryService queryService;
  private final JobEventPublisher jobEventPublisher;
  private final ObjectMapper objectMapper;

  public QueryGeneratedConsumer(JobService jobService, QueryService queryService,
      JobEventPublisher jobEventPublisher, ObjectMapper objectMapper) {
    this.jobService = jobService;
    this.queryService = queryService;
    this.jobEventPublisher = jobEventPublisher;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = JobTopics.QUERY_GENERATED, groupId = "whyql-query")
  public void onQueryGenerated(String payload) {
    JobEvent event;
    try {
      event = objectMapper.readValue(payload, JobEvent.class);
    } catch (Exception e) {
      log.error("Unparseable query.generated payload, dropping: {}", e.getMessage());
      return;
    }

    UUID jobId = event.jobId();
    log.info("Stage 2 (validation+execution) for job {}", jobId);
    Job job = jobService.findById(jobId);

    try {
      if (event.queryPlanJson() == null || event.queryPlanJson().isBlank()) {
        throw new IllegalStateException("event carries no queryPlanJson");
      }
      job.markValidating();
      jobService.save(job);

      QueryPlan plan = objectMapper.readValue(event.queryPlanJson(), QueryPlan.class);
      SchemaSnapshot snapshot = queryService.retrieveSchema(
          event.dataSourceId(), event.ownerUserId());

      // The primary queryText always runs first; sections stack on top.
      // Models sometimes re-list the primary as their first section, so
      // duplicates of it are dropped — it would render twice otherwise.
      // A blank primary is a plan-contract break; the model may have placed
      // everything in sections, so skip it unless there is nothing at all.
      List<QueryPlan.PlanSection> toRun = new ArrayList<>();
      String primarySql = plan.queryText() == null ? "" : plan.queryText().trim();
      if (!primarySql.isEmpty()) {
        toRun.add(new QueryPlan.PlanSection(null, primarySql));
        primarySql = primarySql.toLowerCase(Locale.ROOT);
        for (QueryPlan.PlanSection section : plan.sectionList()) {
          if (section.queryText() != null
              && !section.queryText().trim().toLowerCase(Locale.ROOT).equals(primarySql)) {
            toRun.add(section);
          }
        }
      } else {
        // Blank primary: run whatever sections exist (they were promoted at
        // generation; this covers plans arriving from stored events).
        for (QueryPlan.PlanSection section : plan.sectionList()) {
          if (section.queryText() != null && !section.queryText().isBlank()) {
            toRun.add(section);
          }
        }
      }
      if (toRun.isEmpty()) {
        throw new IllegalStateException("plan carries no executable query text");
      }
      List<ResultEnvelope.Section> built = new ArrayList<>();

      for (QueryPlan.PlanSection section : toRun) {
        QueryPlan sectionPlan = new QueryPlan(
            plan.engine(), section.queryText(), plan.explanation(),
            plan.assumptions(), plan.clarifyingQuestions(), List.of());
        queryService.validateQuery(sectionPlan, snapshot);

        TabularResult result = queryService.executeQuery(
            event.dataSourceId(), event.ownerUserId(), sectionPlan);
        if (result.rows() == null || result.rows().size() <= ResultSizePolicy.TABLE_ROW_LIMIT) {
          built.add(new ResultEnvelope.Section(
              section.title(), ResultEnvelope.SIZE_OK,
              result.rows() == null ? null : (long) result.rows().size(), result));
          continue;
        }
        // Oversized for display: withhold the rows, probe for the true total.
        // A count failure degrades to ">200 rows"; the page offers aggregates.
        Long total = queryService.countRows(
            event.dataSourceId(), event.ownerUserId(), sectionPlan);
        built.add(new ResultEnvelope.Section(
            section.title(), ResultEnvelope.SIZE_TOO_LARGE, total, null));
        log.info("Job {} section '{}' has >{} rows; withheld, total probe = {}",
            jobId, section.title(), ResultSizePolicy.TABLE_ROW_LIMIT, total);
      }

      job.markExecuting();
      jobService.save(job);

      String resultJson = objectMapper.writeValueAsString(
          new ResultEnvelope(ResultEnvelope.KIND_SECTIONS, built));
      job.setResultJson(resultJson);
      jobService.save(job);

      jobEventPublisher.publishQueryExecuted(job);
    } catch (Exception e) {
      log.error("Validation/execution failed for job {}", jobId, e);
      job.markFailed("validation/execution failed: " + e.getMessage());
      jobService.save(job);
      jobEventPublisher.notifyFailed(job);
    }
  }
}
