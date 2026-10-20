package com.whyql.whyql.api.model;

import java.util.List;

/**
 * GraphQL {@code ResultSection}: one answer part of a completed job.
 * {@code sizeCategory} is "OK" (rows present) or "TOO_LARGE" ({@code result} is
 * withheld and {@code totalRows} carries the exact row count when known).
 * A legacy single-result job maps to one section with a null title.
 */
public record ResultSection(
    String title,
    String sizeCategory,
    Long totalRows,
    QueryResult result) {

  public static final String SIZE_OK = "OK";
  public static final String SIZE_TOO_LARGE = "TOO_LARGE";

  /** Empty list when the job has no stored result at all (GENERAL answers, errors). */
  public static List<ResultSection> empty() {
    return List.of();
  }
}
