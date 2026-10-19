package com.whyql.whyql.datasource.event;

import java.util.UUID;

/**
 * Published when a data source is deleted so the schema module can remove its
 * snapshots and drop sandbox schemas. Plain Spring event (same JVM, modular
 * monolith); the schema listener runs after commit.
 */
public record DataSourceDeleted(UUID dataSourceId, String ownerUserId, String sandboxSchema) {}
