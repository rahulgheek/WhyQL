package com.whyql.whyql.api.model;

import java.util.UUID;

/** GraphQL {@code BoardWidgetInput}: create/update + run a board widget. */
public record BoardWidgetInput(
    UUID id,
    String title,
    UUID dataSourceId,
    Board.WidgetMode mode,
    String tableName,
    String sqlText,
    String question,
    Board.ChartKind chartKind,
    int ordinal) {}
