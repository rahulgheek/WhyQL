package com.whyql.whyql.api.model;

import java.util.List;

/** Paged chat sessions for the dashboard: normalized paging envelope. */
public record ChatSessionPage(
    List<ChatSession> items,
    long total,
    int page,
    int size,
    boolean hasNext) {
}
