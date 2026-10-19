package com.whyql.whyql.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.rag.embedding.LexicalEmbeddingModel;

/**
 * Integration test against a real pgvector container: index a snapshot,
 * retrieve relevant docs per question, verify data-source scoping and
 * idempotent re-indexing.
 */
class SchemaDocsServiceIntegrationTest {

  private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16"));

  private static SchemaDocsService docs;

  private static final UUID DS_A = UUID.randomUUID();
  private static final UUID DS_B = UUID.randomUUID();

  private static final SchemaSnapshot ORDERS_SCHEMA = new SchemaSnapshot(
      "shop", List.of(new SchemaSnapshot.SchemaTable(
          "orders",
          List.of(new SchemaSnapshot.SchemaColumn("id", "BIGINT", false, false),
              new SchemaSnapshot.SchemaColumn("amount", "DECIMAL", true, false),
              new SchemaSnapshot.SchemaColumn("status", "TEXT", true, false),
              new SchemaSnapshot.SchemaColumn("internal_flag", "BOOLEAN", true, true)),
          List.of("id"),
          List.of(new SchemaSnapshot.ForeignKey(List.of("customer_id"), "customers",
              List.of("id"))))),
      Instant.now());

  @BeforeAll
  static void startContainerAndIndex() throws Exception {
    PG.start();
    SimpleDriverDataSource ds = new SimpleDriverDataSource();
    ds.setDriver(new org.postgresql.Driver());
    ds.setUrl(PG.getJdbcUrl());
    ds.setUsername(PG.getUsername());
    ds.setPassword(PG.getPassword());

    JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
    jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");

    VectorStore store = PgVectorStore.builder(jdbcTemplate, new LexicalEmbeddingModel())
        .dimensions(SchemaDocsService.DIMENSIONS)
        .idType(PgVectorStore.PgIdType.TEXT)
        .initializeSchema(true)
        .build();
    // In the application context Spring invokes this; standalone we must.
    ((org.springframework.beans.factory.InitializingBean) store).afterPropertiesSet();
    docs = new SchemaDocsService(store);

    docs.index(DS_A, ORDERS_SCHEMA);
  }

  @AfterAll
  static void stopContainer() {
    PG.stop();
  }

  @Test
  void searchReturnsRelevantTableDoc() {
    List<String> hits = docs.search(DS_A, "show me orders revenue amount", 3);
    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0)).contains("orders");
  }

  @Test
  void hiddenColumnsNeverAppearInDocs() {
    List<String> hits = docs.search(DS_A, "internal flag", 10);
    assertThat(String.join(" | ", hits)).doesNotContain("internal_flag");
  }

  @Test
  void searchScopedToDataSource() {
    // Different data source: nothing indexed -> no results, no leakage from DS_A.
    assertThat(docs.search(DS_B, "show me orders revenue amount", 5)).isEmpty();
  }

  @Test
  void emptyQuestionYieldsNoResults() {
    assertThat(docs.search(DS_A, "   ", 5)).isEmpty();
  }

  @Test
  void reindexReplacesDocuments() {
    SchemaSnapshot renamed = new SchemaSnapshot(
        "shop", List.of(new SchemaSnapshot.SchemaTable(
            "sales_orders",
            List.of(new SchemaSnapshot.SchemaColumn("id", "BIGINT", false, false)),
            List.of("id"), List.of())),
        Instant.now());
    docs.index(DS_A, renamed);

    List<String> hits = docs.search(DS_A, "orders sales", 10);
    String all = String.join(" | ", hits);
    assertThat(all).contains("sales_orders");
    // The old table document must not survive the re-index.
    assertThat(hits.stream().noneMatch(h ->
        h.startsWith("Table orders:"))).isTrue();
  }

  @Test
  void reindexKeepsExemplarMemory() {
    UUID selfDs = UUID.randomUUID();
    docs.memorize(selfDs, "what is monthly revenue?", "SELECT sum(amount) FROM orders");
    docs.index(selfDs, ORDERS_SCHEMA);

    List<SchemaDocsService.JobExemplar> exemplars =
        docs.searchExemplars(selfDs, "tell me our monthly revenue", 5);
    assertThat(exemplars).isNotEmpty();
    assertThat(exemplars.get(0).sql()).isEqualTo("SELECT sum(amount) FROM orders");
  }

  @Test
  void memorizedExemplarIsRetrievableAsFewShotInput() {
    docs.memorize(DS_A, "what is our monthly revenue?",
        "SELECT date_trunc('month', created_at), sum(amount) FROM orders GROUP BY 1");

    List<SchemaDocsService.JobExemplar> exemplars =
        docs.searchExemplars(DS_A, "tell me the monthly revenue", 5);

    assertThat(exemplars).isNotEmpty();
    assertThat(exemplars.get(0).question()).containsIgnoringCase("monthly revenue");
    assertThat(exemplars.get(0).sql()).contains("sum(amount)");
    // Exemplar documents must carry their validated SQL.
    assertThat(exemplars.stream().noneMatch(e -> e.sql() == null || e.sql().isBlank()))
        .isTrue();
  }

  @Test
  void sameQuestionUpsertsInsteadOfDuplicating() {
    docs.memorize(DS_A, "what is our monthly revenue?", "SELECT old_sql FROM t");
    docs.memorize(DS_A, "What is our monthly revenue?", "SELECT new_sql FROM t");

    List<SchemaDocsService.JobExemplar> exemplars =
        docs.searchExemplars(DS_A, "monthly revenue", 10);

    assertThat(exemplars).hasSize(1);
    assertThat(exemplars.get(0).sql()).isEqualTo("SELECT new_sql FROM t");
  }

  @Test
  void exemplarSearchScopedToDataSource() {
    docs.memorize(DS_B, "what is our monthly revenue?", "SELECT 1");

    assertThat(docs.searchExemplars(DS_B, "monthly revenue", 5))
        .anyMatch(e -> e.sql().equals("SELECT 1"));
    assertThat(docs.searchExemplars(DS_A, "tell me the monthly revenue", 5)
        .stream().allMatch(e -> !e.sql().equals("SELECT 1")))
        .isTrue();
  }

  @Test
  void schemaDocSearchNeverReturnsExemplars() {
    docs.memorize(DS_A, "what is our monthly revenue?", "SELECT sum(amount) FROM orders");

    // Query nearly identical to the exemplar: if the kind filter were broken,
    // the exemplar (similarity ~1.0) would be the top hit here.
    List<String> schemaDocs = docs.search(DS_A, "what is our monthly revenue?", 10);
    assertThat(schemaDocs).noneMatch(line ->
        line.contains("what is our monthly revenue") || line.contains("SELECT "));
  }

  @Test
  void evictRemovesSchemaDocsAndExemplars() {
    UUID selfDs = UUID.randomUUID();
    docs.index(selfDs, ORDERS_SCHEMA);
    docs.memorize(selfDs, "what is our monthly revenue?", "SELECT sum(amount) FROM orders");
    docs.evict(selfDs);

    assertThat(docs.search(selfDs, "orders amount", 10)).isEmpty();
    assertThat(docs.searchExemplars(selfDs, "monthly revenue", 10)).isEmpty();
  }
}
