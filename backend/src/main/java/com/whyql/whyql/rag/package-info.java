/**
 * Rag: vector index of schema documentation over pgvector.
 *
 * Feeds the SQL generation prompt with the data source's most relevant
 * tables/columns (semantic retrieval) so large schemas stay inside the
 * conversational context and generation is grounded. Documents are indexed
 * from {@code SchemaSnapshot}s at retrieval time in the query pipeline.
 */
@ApplicationModule(displayName = "Rag", allowedDependencies = { "datasource::domain", "datasource::events" })
package com.whyql.whyql.rag;

import org.springframework.modulith.ApplicationModule;
