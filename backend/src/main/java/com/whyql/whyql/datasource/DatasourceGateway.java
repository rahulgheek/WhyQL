package com.whyql.whyql.datasource;

import java.util.List;
import java.util.UUID;

/**
 * The datasource module's public door for other modules (schema sync, query
 * execution). Credential handling, decryption, sandbox resolution and adapter
 * selection all stay inside this module; callers only pass IDs plus their owner.
 */
public interface DatasourceGateway {

  /** Metadata for one owned data source; throws when missing or not owned. */
  DataSourceView get(UUID dataSourceId, String ownerUserId);

  /** Live introspection of an owned data source (used by schema sync). */
  SchemaSnapshot introspect(UUID dataSourceId, String ownerUserId);

  /** Execute an already-validated plan against an owned data source. */
  TabularResult execute(UUID dataSourceId, String ownerUserId, QueryPlan plan,
      ExecutionLimits limits, VisibilityRules rules);

  /** All data sources owned by a user. */
  List<DataSourceView> list(String ownerUserId);

  /** Persist a sandbox source created by SQL-file upload. */
  DataSourceView createSandbox(String ownerUserId, String name, String sandboxSchema);
}
