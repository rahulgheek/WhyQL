package com.whyql.whyql.datasource.domain;

import java.util.List;

/**
 * The model's validated answer to a question: which engine to run on, the query
 * text, and its reasoning. Produced by the LLM in Phase 4; executed only after the
 * validator gate passes. A non-empty {@code clarifyingQuestions} ends the job in
 * NEEDS_CLARIFICATION instead of executing.
 */
public record QueryPlan(
    Engine engine,
    String queryText,
    String explanation,
    List<String> assumptions,
    List<String> clarifyingQuestions) {}
