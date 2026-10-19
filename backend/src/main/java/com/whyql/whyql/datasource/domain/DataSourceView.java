package com.whyql.whyql.datasource.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Credential-free view of a data source. Secrets never leave the datasource module;
 * other modules open connections through {@link DatasourceGateway} instead.
 */
public record DataSourceView(
    UUID id,
    String name,
    Engine engine,
    boolean sandbox,
    Instant createdAt) {}
