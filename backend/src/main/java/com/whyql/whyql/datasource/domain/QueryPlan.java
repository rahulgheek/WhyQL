package com.whyql.whyql.datasource.domain;

import java.util.List;

/**
 * The model's validated answer to a question: which engine to run on, the query
 * text, and its reasoning. Produced by the LLM in Phase 4; executed only after the
 * validator gate passes. A non-empty {@code clarifyingQuestions} ends the job in
 * NEEDS_CLARIFICATION instead of executing.
 *
 * <p>{@code sections} carries optional additional sub-queries for questions that
 * legitimately ask for several views ("product summary and monthly trend"):
 * each section is a full, self-contained SELECT executed and surfaced
 * separately.{@code queryText} is always the primary/first answer — sections
 * never replace it.
 */
public record QueryPlan(
    Engine engine,
    String queryText,
    String explanation,
    List<String> assumptions,
    List<String> clarifyingQuestions,
    List<PlanSection> sections) {

  /** One extra result view: a short display title plus standalone SQL. */
  public record PlanSection(String title, String queryText) {}

  public QueryPlan(Engine engine, String queryText, String explanation,
      List<String> assumptions, List<String> clarifyingQuestions) {
    this(engine, queryText, explanation, assumptions, clarifyingQuestions, List.of());
  }

  /** Sections as a never-null list. */
  public List<PlanSection> sectionList() {
    return sections == null ? List.of() : sections;
  }
}
