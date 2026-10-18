package com.whyql.whyql.jobs.event;

import java.util.List;

/**
 * Kafka topics for the stage pipeline. Every stage topic has a symmetric DLQ
 * topic so publishes that fail after retries are never silently dropped.
 */
public final class JobTopics {

  public static final String QUERY_REQUESTED = "query.requested";
  public static final String QUERY_REQUESTED_DLQ = "query.requested.dlq";
  public static final String QUERY_GENERATED = "query.generated";
  public static final String QUERY_GENERATED_DLQ = "query.generated.dlq";
  public static final String QUERY_EXECUTED = "query.executed";
  public static final String QUERY_EXECUTED_DLQ = "query.executed.dlq";
  public static final String ANALYSIS_REQUESTED = "analysis.requested";
  public static final String ANALYSIS_REQUESTED_DLQ = "analysis.requested.dlq";
  public static final String SCHEMA_INGESTION_REQUESTED = "schema.ingestion.requested";
  public static final String SCHEMA_INGESTION_REQUESTED_DLQ = "schema.ingestion.requested.dlq";

  public static final List<String> ALL_TOPICS = List.of(
      QUERY_REQUESTED, QUERY_REQUESTED_DLQ,
      QUERY_GENERATED, QUERY_GENERATED_DLQ,
      QUERY_EXECUTED, QUERY_EXECUTED_DLQ,
      ANALYSIS_REQUESTED, ANALYSIS_REQUESTED_DLQ,
      SCHEMA_INGESTION_REQUESTED, SCHEMA_INGESTION_REQUESTED_DLQ
  );

  private JobTopics() {}
}
