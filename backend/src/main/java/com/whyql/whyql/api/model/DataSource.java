package com.whyql.whyql.api.model;

import com.whyql.whyql.datasource.domain.DataSourceView;

/** GraphQL {@code DataSource}: credential-free view of a data source. */
public record DataSource(
    java.util.UUID id,
    String name,
    com.whyql.whyql.datasource.domain.Engine engine,
    boolean sandbox,
    String createdAt) {

  public static DataSource from(DataSourceView view) {
    return new DataSource(
        view.id(), view.name(), view.engine(), view.sandbox(), view.createdAt().toString());
  }
}
