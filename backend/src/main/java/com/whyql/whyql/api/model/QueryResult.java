package com.whyql.whyql.api.model;

import java.util.List;

/** GraphQL {@code QueryResult}: a {@code TabularResult} shaped for clients. */
public record QueryResult(
    List<ResultColumn> columns,
    List<List<Object>> rows,
    boolean truncated) {

  public record ResultColumn(String name, String typeName) {}

  public static QueryResult from(com.whyql.whyql.datasource.domain.TabularResult result) {
    return new QueryResult(
        result.columns().stream()
            .map(c -> new ResultColumn(c.name(), c.typeName()))
            .toList(),
        result.rows(),
        result.truncated());
  }
}
