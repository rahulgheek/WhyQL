package com.whyql.whyql.query;

import java.util.List;
import java.util.UUID;

import com.whyql.whyql.datasource.QueryPlan;
import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.TabularResult;

/**
 * Request and result shapes for the query pipeline.
 */
public interface QueryService {

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
  QueryPlan generateQuery(UUID dataSourceId, SchemaSnapshot snapshot, String question);

  /**
   * Validate a QueryPlan against the schema (Stage 3: validation).
   */
  QueryPlan validateQuery(QueryPlan plan, SchemaSnapshot snapshot);

  /**
   * Execute a validated QueryPlan (Stage 4: execution).
   */
  TabularResult executeQuery(UUID dataSourceId, String ownerUserId, QueryPlan plan);
}
