package com.whyql.whyql.api.dashboard;

import org.springframework.stereotype.Component;

import com.whyql.whyql.api.model.DashboardCard;
import com.whyql.whyql.api.model.QueryResult;
import com.whyql.whyql.datasource.domain.TabularResult;

import tools.jackson.databind.ObjectMapper;

/**
 * Maps dashboard card entities to the GraphQL {@code DashboardCard} shape,
 * parsing the snapshotted result JSON.
 */
@Component
public class DashboardCardMapper {

  private final ObjectMapper objectMapper;

  public DashboardCardMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public DashboardCard toGql(com.whyql.whyql.dashboard.domain.DashboardCard entity) {
    return new DashboardCard(
        entity.getId(),
        entity.getJobId(),
        entity.getDataSourceId(),
        entity.getQuestion(),
        entity.getEngine(),
        toResult(entity.getResultJson()),
        entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
  }

  private QueryResult toResult(String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      TabularResult parsed = objectMapper.readValue(resultJson, TabularResult.class);
      return QueryResult.from(parsed);
    } catch (Exception e) {
      // A corrupted snapshot shouldn't 500 the dashboard; render an empty card
      // result area instead of failing the whole listing.
      return null;
    }
  }
}
