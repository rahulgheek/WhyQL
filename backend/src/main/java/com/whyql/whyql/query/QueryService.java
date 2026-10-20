package com.whyql.whyql.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;

/**
 * Request and result shapes for the query pipeline.
 */
public interface QueryService {

  /** Router decision values (see {@link #routeQuestion(String)}). */
  String ROUTE_DATA_QUERY = "DATA_QUERY";
  String ROUTE_QUERY_AND_EXPLAIN = "QUERY_AND_EXPLAIN";
  String ROUTE_GENERAL = "GENERAL";
  record QueryRequest(
      UUID dataSourceId,
      String question,
      List<String> analysisTypes,
      String chartPreference,
      String specFormat) {}

  record QueryResult(
      String status,
      QueryPlan plan,
      TabularResult result,
      String errorMessage,
      List<String> clarifyingQuestions) {}

  /**
   * Run the full question-to-query pipeline synchronously:
   * schema retrieval, SQL generation, validation, execution, self-correction.
   */
  QueryResult askQuestion(QueryRequest request);

  /**
   * Retrieve schema for a data source (Stage 1: schema retrieval).
   */
  SchemaSnapshot retrieveSchema(UUID dataSourceId, String ownerUserId);

  /**
   * Generate a QueryPlan from a question and schema (Stage 2: query generation).
   * RAG-retrieved schema context is scoped by data source.
   */
  /**
   * Generate a query plan for a data source. The engine is resolved from the
   * data source record itself — prompts, plan tagging and validation are per
   * engine; hardcoding was the source of cross-dialect SQL bugs.
   */
  QueryPlan generateQuery(UUID dataSourceId, String ownerUserId,
      SchemaSnapshot snapshot, String question);

  /**
   * Validate a QueryPlan against the schema (Stage 3: validation).
   */
  QueryPlan validateQuery(QueryPlan plan, SchemaSnapshot snapshot);

  /**
   * Execute a validated QueryPlan (Stage 4: execution).
   */
  TabularResult executeQuery(UUID dataSourceId, String ownerUserId, QueryPlan plan);

  /**
   * Instant answers for questions about the schema itself ("how many tables
   * are there", "what tables do I have"): derived straight from a live
   * introspection of the data source — no LLM, no SQL, never asks for
   * clarification. Empty when the question is a data question and must run
   * through the normal pipeline.
   */
  Optional<TabularResult> answerSchemaQuestion(UUID dataSourceId, String ownerUserId,
      String question);

  /**
   * True when the question leads with explanation intent ("why are these
   * people not in user profile"): SQL supplies evidence rows, the analysis
   * stage supplies the narrative.
   */
  boolean wantsExplanation(String question);

  /**
   * LLM routing (Stage 1): one small classification call deciding the path —
   * {@link #ROUTE_DATA_QUERY} (SQL answer), {@link #ROUTE_QUERY_AND_EXPLAIN}
   * (SQL evidence + narrative reasoning) or {@link #ROUTE_GENERAL} (no
   * database involvement). Never throws; any router failure answers DATA_QUERY
   * so the pipeline degrades to today's behavior, never to a broken job.
   */
  String routeQuestion(String question);

  /**
   * Direct prose answer for ROUTE_GENERAL questions — no SQL, no database.
   * Null when the LLM fails; the caller surfaces that as normal prose.
   */
  String answerGeneral(String question);

  /**
   * Grounded narrative for a why-question over executed evidence rows.
   * Never throws — a narrator failure shortens to null and the client keeps
   * the plain result table.
   */
  String analyzeExplanation(String question, TabularResult result);

  /**
   * How many rows the query would return in total, via a cheap read-only
   * probe ({@code SELECT COUNT(*) FROM ( query ) q}). Used when a result
   * exceeds the table size policy so the client can say "2,341,105 rows" instead
   * of "too many to count". Null when the probe fails (or answer unreadable) —
   * callers degrade to a ">N rows" phrasing, never an error.
   */
  Long countRows(UUID dataSourceId, String ownerUserId, QueryPlan plan);
}
