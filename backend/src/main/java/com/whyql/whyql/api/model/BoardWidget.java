package com.whyql.whyql.api.model;

import java.util.UUID;

/** GraphQL {@code BoardWidget}: one slot on a presentation board. */
public record BoardWidget(
    UUID id,
    UUID boardId,
    String title,
    UUID dataSourceId,
    Board.WidgetMode mode,
    String tableName,
    String sqlText,
    String question,
    Board.ChartKind chartKind,
    int ordinal,
    String status,
    String errorMessage,
    QueryResult result,
    String createdAt,
    String updatedAt) {}
