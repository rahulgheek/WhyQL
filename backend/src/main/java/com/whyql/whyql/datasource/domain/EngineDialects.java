package com.whyql.whyql.datasource.domain;

import java.util.List;

/**
 * Per-engine SQL dialect guidance for the LLM generation prompt. Lives in the
 * module root so other modules (query/prompt) may read it without touching
 * adapter internals; the {@link Engine} tables below are the single source of
 * truth — add an engine, add its dialect block here.
 */
public final class EngineDialects {

  private EngineDialects() {}

  /** The dialect name shown in prompts and error text. */
  public static String displayName(Engine engine) {
    return switch (engine) {
      case POSTGRES -> "PostgreSQL";
      case MYSQL -> "MySQL";
    };
  }

  /** Engine-specific SQL rules injected into the system prompt. */
  public static List<String> hints(Engine engine) {
    return switch (engine) {
      case POSTGRES -> List.of(
          "Tables may live in several schemas; when two tables share a name the "
              + "schema listing shows them qualified as schema.table — reference them exactly that way.",
          "String concatenation uses || ; case-insensitive matching uses ILIKE.",
          "Booleans are TRUE/FALSE literals; cast when needed with ::type.",
          "Always end with a LIMIT clause.");
      case MYSQL -> List.of(
          "MySQL has no schema layer — tables are referenced by their bare name.",
          "There is no ILIKE: case-insensitive matching is LOWER(col) LIKE '%x%'.",
          "Use CURDATE(), DATE_SUB(…, INTERVAL n DAY), DATE_FORMAT() for date work.",
          "Reference identifiers bare; only quote them when the name demands it.",
          "Always end with a LIMIT clause.");
    };
  }
}
