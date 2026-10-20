package com.whyql.whyql.api.model;

import java.util.UUID;

/** GraphQL {@code AskQuestionInput}: which data source, and the question. */
public record AskQuestionInput(UUID dataSourceId, String question, UUID chatSessionId) {

  /** Backwards-compatible ctor for tests/code predating chat sessions. */
  public AskQuestionInput(UUID dataSourceId, String question) {
    this(dataSourceId, question, null);
  }
}
