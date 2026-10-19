package com.whyql.whyql.datasource.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.whyql.whyql.datasource.adapter.DataSourceAdapter;
import com.whyql.whyql.datasource.service.DataSourceErrorCode;
import com.whyql.whyql.datasource.service.DataSourceException;
import com.whyql.whyql.datasource.domain.DataSourceView;
import com.whyql.whyql.datasource.service.DatasourceGateway;
import com.whyql.whyql.datasource.domain.ExecutionLimits;
import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.datasource.domain.VisibilityRules;

/**
 * Gateway implementation: resolves owned data sources to connections (decrypting
 * URL credentials or routing sandbox sources to the app database) and delegates to
 * the matching adapter. The only way other modules touch live databases.
 */
@Service
public class DatasourceGatewayImpl implements DatasourceGateway {

  private final DataSourceService service;
  private final DataSourceAdapter adapter;

  public DatasourceGatewayImpl(DataSourceService service, DataSourceAdapter adapter) {
    this.service = service;
    this.adapter = adapter;
  }

  @Override
  public DataSourceView get(UUID dataSourceId, String ownerUserId) {
    return service.get(dataSourceId, ownerUserId);
  }

  @Override
  public List<DataSourceView> list(String ownerUserId) {
    return service.list(ownerUserId);
  }

  @Override
  public DataSourceView createSandbox(String ownerUserId, String name, String sandboxSchema) {
    return service.saveSandbox(ownerUserId, name, sandboxSchema);
  }

  @Override
  public SchemaSnapshot introspect(UUID dataSourceId, String ownerUserId) {
    return service.introspect(dataSourceId, ownerUserId);
  }

  @Override
  public TabularResult execute(UUID dataSourceId, String ownerUserId, QueryPlan plan,
      ExecutionLimits limits, VisibilityRules rules) {
    DataSourceAdapter.ConnectionParams params = service.connectionParams(dataSourceId, ownerUserId);
    if (params.schema() != null) {
      throw new DataSourceException(DataSourceErrorCode.ENGINE_MISMATCH,
          "query execution on sandbox sources arrives with the query pipeline in Phase 4");
    }
    try {
      adapter.validate(plan, null, rules);
      return adapter.execute(plan, params, limits, rules);
    } catch (DataSourceAdapter.AdapterException e) {
      throw new DataSourceException(DataSourceErrorCode.EXECUTION_FAILED,
          "execution failed: " + e.getMessage(), e);
    }
  }
}
