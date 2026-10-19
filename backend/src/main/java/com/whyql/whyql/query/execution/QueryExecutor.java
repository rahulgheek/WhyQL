package com.whyql.whyql.query.execution;

import java.sql.SQLException;

import org.springframework.stereotype.Component;

import java.util.UUID;

import com.whyql.whyql.datasource.adapter.DataSourceAdapter;
import com.whyql.whyql.datasource.adapter.DataSourceAdapter.ConnectionParams;
import com.whyql.whyql.datasource.adapter.DataSourceAdapter.AdapterException;
import com.whyql.whyql.datasource.service.DatasourceGateway;
import com.whyql.whyql.datasource.domain.ExecutionLimits;
import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.datasource.domain.VisibilityRules;

/**
 * Executes a validated QueryPlan through the DatasourceGateway (which routes to
 * the correct DataSourceAdapter). Connections are short-lived and read-only.
 */
@Component
public class QueryExecutor {

  private final DatasourceGateway gateway;

  public QueryExecutor(DatasourceGateway gateway) {
    this.gateway = gateway;
  }

  /**
   * Execute a validated plan against the given data source.
   * Returns the TabularResult or throws AdapterException on failure.
   */
  public TabularResult execute(UUID dataSourceId, String ownerUserId,
      QueryPlan plan, ExecutionLimits limits, VisibilityRules rules) {
    return gateway.execute(dataSourceId, ownerUserId, plan, limits, rules);
  }

  /** Default limits for query execution. */
  public static ExecutionLimits defaultLimits() {
    return ExecutionLimits.defaults();
  }
}
