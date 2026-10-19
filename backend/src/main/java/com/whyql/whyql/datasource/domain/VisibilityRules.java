package com.whyql.whyql.datasource.domain;

import java.util.Set;

/**
 * Columns the user hid from AI ("table.column"). The validator rejects any query
 * touching them and the executor strips them from results as a second enforcement
 * layer — the flag is enforced in code, not only in the prompt.
 */
public record VisibilityRules(Set<String> hiddenColumns) {

  public static VisibilityRules none() {
    return new VisibilityRules(Set.of());
  }

  /** Snapshot-derived rules: every column flagged hidden in the stored snapshot. */
  public static VisibilityRules fromSnapshot(SchemaSnapshot snapshot) {
    Set<String> hidden = new java.util.HashSet<>();
    for (SchemaSnapshot.SchemaTable table : snapshot.tables()) {
      for (SchemaSnapshot.SchemaColumn column : table.columns()) {
        if (column.hidden()) {
          hidden.add(table.name() + "." + column.name());
        }
      }
    }
    return new VisibilityRules(Set.copyOf(hidden));
  }
}
