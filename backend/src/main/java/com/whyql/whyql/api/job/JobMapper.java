package com.whyql.whyql.api.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.whyql.whyql.api.model.Job;
import com.whyql.whyql.api.model.QueryResult;
import com.whyql.whyql.api.model.ResultSection;
import com.whyql.whyql.datasource.domain.ResultEnvelope;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.jobs.domain.JobStatus;

import tools.jackson.databind.ObjectMapper;

/**
 * Maps job entities/events to the GraphQL {@code Job} shape, including the
 * progress curve, result-JSON parsing, and the clarifying questions stored in
 * the query plan for NEEDS_CLARIFICATION jobs.
 */
@Component
public class JobMapper {

  private final ObjectMapper objectMapper;

  public JobMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Job toGql(com.whyql.whyql.jobs.domain.Job entity) {
    List<ResultSection> sections = toSections(entity.getResultJson());
    return new Job(
        entity.getId(),
        entity.getStatus().name(),
        progress(entity.getStatus()),
        entity.getOwnerUserId(),
        entity.getDataSourceId(),
        entity.getQuestion(),
        entity.getErrorMessage(),
        primaryResult(sections),
        sections,
        entity.getAnalysis(),
        toClarifying(entity.getQueryPlanJson()),
        entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
        entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
  }

  public Job fromEvent(com.whyql.whyql.jobs.event.JobEvent event) {
    List<ResultSection> sections = toSections(event.resultJson());
    return new Job(
        event.jobId(),
        event.status().name(),
        progress(event.status()),
        event.ownerUserId(),
        event.dataSourceId(),
        event.question(),
        event.errorMessage(),
        primaryResult(sections),
        sections,
        event.analysis(),
        toClarifying(event.queryPlanJson()),
        null,
        null);
  }

  /**
   * Extracts the LLM's clarifying questions from the stored query plan JSON;
   * empty unless the plan is missing/invalid. Never throws.
   */
  @SuppressWarnings("unchecked")
  private List<String> toClarifying(String queryPlanJson) {
    if (queryPlanJson == null || queryPlanJson.isBlank()) {
      return List.of();
    }
    try {
      var parsed = objectMapper.readValue(queryPlanJson, java.util.Map.class);
      Object questions = parsed.get("clarifyingQuestions");
      if (questions instanceof List<?> list) {
        return list.stream().filter(String.class::isInstance)
            .map(String.class::cast).toList();
      }
      return List.of();
    } catch (Exception e) {
      return List.of();
    }
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

  /**
   * Result sections for clients: a sections envelope parses part by part; the
   * legacy single-result JSON wraps as one unnamed OK section. An empty list
   * means no stored result (GENERAL answers, failures) — never an error.
   */
  private List<ResultSection> toSections(String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return List.of();
    }
    try {
      ResultEnvelope envelope = ResultEnvelope.parse(resultJson, objectMapper);
      if (envelope == null) {
        return List.of();
      }
      List<ResultSection> sections = new ArrayList<>();
      for (ResultEnvelope.Section section : envelope.sections()) {
        sections.add(new ResultSection(
            section.title(),
            section.sizeCategory(),
            section.totalRows(),
            section.result() == null ? null : toQueryResult(section.result())));
      }
      return List.copyOf(sections);
    } catch (Exception e) {
      // Unparseable stored results must not 500 the job view.
      return List.of();
    }
  }

  /** The first section that actually carries rows; null while none rendered. */
  private QueryResult primaryResult(List<ResultSection> sections) {
    return sections.stream()
        .map(ResultSection::result)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private QueryResult toQueryResult(TabularResult parsed) {
    return new QueryResult(
        parsed.columns().stream()
            .map(c -> new QueryResult.ResultColumn(c.name(), c.typeName()))
            .toList(),
        parsed.rows().stream()
            .map(this::normalizeRow)
            .toList(),
        parsed.truncated());
  }

  /**
   * Legacy stored results may contain driver-wrapper objects ({"type":…,
   * "value":…} from PGobject cells) from before cell normalization existed;
   * unwrap or stringify them so clients never render "[object Object]".
   */
  private List<Object> normalizeRow(List<Object> row) {
    if (row == null) {
      return List.of();
    }
    return row.stream().map(cell -> {
      if (cell instanceof Map<?, ?> map) {
        Object value = map.get("value");
        if (map.size() == 2 && map.containsKey("type") && value != null) {
          return value; // unwrap the classic PGobject shape
        }
        return safeJson(map);
      }
      if (cell instanceof List<?> nested) {
        return normalizeRow((List<Object>) nested);
      }
      return cell;
    }).toList();
  }

  private String safeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return String.valueOf(value);
    }
  }
}
