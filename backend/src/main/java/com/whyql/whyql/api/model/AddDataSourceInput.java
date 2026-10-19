package com.whyql.whyql.api.model;

import com.whyql.whyql.datasource.domain.Engine;

/**
 * GraphQL {@code AddDataSourceInput} — data source Url submit. Username and
 * password are optional (local dev Postgres often needs neither).
 */
public record AddDataSourceInput(
    String name,
    Engine engine,
    String jdbcUrl,
    String username,
    String password) {
}
