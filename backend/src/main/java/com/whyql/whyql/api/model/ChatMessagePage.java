package com.whyql.whyql.api.model;

import java.util.List;

/**
 * Paged messages. Pages count backwards: page 0 is the newest window, and
 * items come back ascending within it, so a chat UI prepends each older
 * page above what it already has.
 */
public record ChatMessagePage(
    List<ChatMessage> items,
    long total,
    int page,
    int size,
    boolean hasNext) {
}
