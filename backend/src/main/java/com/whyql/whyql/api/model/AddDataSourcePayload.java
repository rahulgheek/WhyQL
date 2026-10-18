package com.whyql.whyql.api.model;

import java.util.List;

/** GraphQL {@code AddDataSourcePayload} — created data source plus sandbox warnings. */
public record AddDataSourcePayload(
    DataSource dataSource,
    List<String> warnings) {

  public static AddDataSourcePayload from(
      com.whyql.whyql.datasource.service.DataSourceService.AddResult result) {
    return new AddDataSourcePayload(DataSource.from(result.view()), result.warnings());
  }
}
