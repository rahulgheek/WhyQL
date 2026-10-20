package com.whyql.whyql.datasource.domain;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a completed job hands to clients: one or more result sections, each with
 * a size decision. A small section ships its rows ({@link #SIZE_OK}); an
 * oversized section withholds them and carries the true row count instead —
 * raw dumps of millions of rows never cross the wire, while aggregated
 * (chart-friendly) results stay small and sail through.
 *
 * <p>Stored in {@code jobs.result_json}. Parsing accepts both shapes: the
 * legacy single-table JSON written before sections existed wraps as a one-part
 * envelope with {@code kind == null}, so old completed jobs keep rendering.
 */
public record ResultEnvelope(String kind, List<Section> sections) {

  public static final String KIND_SECTIONS = "SECTIONS";

  public static final String SIZE_OK = "OK";
  public static final String SIZE_TOO_LARGE = "TOO_LARGE";

  /**
   * One answer part: display title (null for the plain single result), the size
   * decision, the exact row count when known (oversized sections only), and the
   * rows themselves (null when withheld).
   */
  public record Section(
      String title,
      String sizeCategory,
      Long totalRows,
      TabularResult result) {}

  public static ResultEnvelope single(TabularResult result) {
    return new ResultEnvelope(null, List.of(
        new Section(null, SIZE_OK, result == null ? null : (long) result.rows().size(),
            result)));
  }

  /**
   * Parses stored result JSON of either shape into an envelope; null when the
   * JSON is absent or unparseable — callers must not fail on stored garbage.
   */
  public static ResultEnvelope parse(String resultJson, ObjectMapper objectMapper) {
    return parse(resultJson, objectMapper, true);
  }

  static ResultEnvelope parse(String resultJson, ObjectMapper objectMapper,
      boolean parseLegacy) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      JsonNode root = objectMapper.readTree(resultJson);
      if (root == null || !root.isObject()) {
        return null;
      }
      if (root.has("kind") && root.has("sections")) {
        ResultEnvelope envelope = objectMapper.readValue(resultJson, ResultEnvelope.class);
        if (envelope == null || envelope.sections() == null || envelope.sections().isEmpty()) {
          return null;
        }
        return envelope;
      }
      // Legacy single TabularResult {columns, rows, truncated}.
      if (parseLegacy && root.has("columns") && root.has("rows")) {
        TabularResult legacy = objectMapper.readValue(resultJson, TabularResult.class);
        return single(legacy);
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  /** The first section that actually carries rows (dashboards reuse this). */
  public TabularResult primaryResult() {
    if (sections == null) {
      return null;
    }
    for (Section section : sections) {
      if (section.result() != null) {
        return section.result();
      }
    }
    return null;
  }
}
