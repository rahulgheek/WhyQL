package com.whyql.whyql.rag.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.whyql.whyql.rag.embedding.LexicalEmbeddingModel;
import com.whyql.whyql.rag.service.SchemaDocsService;

/**
 * Rag wiring: the local lexical embedding model and the pgvector store.
 *
 * {@code spring.ai.vectorstore.type: none} keeps the store autoconfiguration
 * (and its OpenAI-oriented defaults) out of the way; this config owns both
 * beans instead. The store's table is created on first boot via
 * {@code initializeSchema(true)} against the pgvector-enabled Postgres image
 * (extension created in V1__baseline.sql).
 */
@Configuration
public class RagConfig {

  /** Local embedder is the one {@link EmbeddingModel} in the application. */
  @Bean
  @Primary
  public EmbeddingModel lexicalEmbeddingModel() {
    return new LexicalEmbeddingModel();
  }

  /**
   * Schema-doc vector store: cosine similarity over {@link
   * SchemaDocsService#DIMENSIONS}-dimensional lexical embeddings.
   */
  @Bean
  public VectorStore schemaVectorStore(JdbcTemplate jdbcTemplate,
      EmbeddingModel embeddingModel) {
    return PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .dimensions(SchemaDocsService.DIMENSIONS)
        .idType(PgVectorStore.PgIdType.TEXT)
        .initializeSchema(true)
        .build();
  }
}
