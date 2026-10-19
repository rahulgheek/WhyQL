package com.whyql.whyql.api.model;

import java.util.List;
import java.util.UUID;

/** GraphQL {@code Board}: a presentation board and its widgets. */
public record Board(
    UUID id,
    String title,
    List<BoardWidget> widgets,
    String createdAt) {

  /** Widget data modes: manual table pick, raw SQL, or a natural-language prompt. */
  public enum WidgetMode {
    MANUAL, SQL, QUESTION
  }

  /** How a widget renders its result. */
  public enum ChartKind {
    TABLE, BAR, LINE, PIE
  }
}
