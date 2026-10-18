package com.whyql.whyql.rag.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import com.whyql.whyql.datasource.SchemaSnapshot;

/**
 * Schema-document vector index.
 *
 * Indexes a data source's {@link SchemaSnapshot} into the pgvector store as
 * one document per table (visible columns) and one per visible column, then
 * serves top-k retrieval of the most relevant docs for a natural-language
 * question. Re-indexing is idempotent per data source: schema documents are
 * removed by metadata filter (exemplar memory is untouched) before the new
 * set is stored.
 *
 * The store also carries the org's answered-question memory: every completed
 * job's question→SQL pair is memorized per data source ({@link #memorize}
 * upserts, keyed on a normalized-question id, so re-answering the same
 * question refreshes the SQL instead of duplicating) and retrieved as
 * validated few-shot exemplars ({@link #searchExemplars}).
 */
@Service
public class SchemaDocsService {

  /** Dimensionality of the configured embedding model (local lexical embeddings). */
  public static final int DIMENSIONS = 512;

  /** Metadata keys used for scoping and identification inside the vector store. */
  static final String META_DATASOURCE_ID = "datasource_id";
  static final String META_KIND = "kind";
  static final String META_TABLE = "table";
  static final String META_SQL = "sql";
  static final String KIND_TABLE = "table";
  static final String KIND_COLUMN = "column";
  static final String KIND_EXEMPLAR = "exemplar";

  /** One answered question from the org's memory, retrievable as few-shot input. */
  public record JobExemplar(String question, String sql) {}

  private final VectorStore vectorStore;

  public SchemaDocsService(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  /**
   * Index (or re-index) the snapshot's schema docs for a data source. Only
   * table/column kinds are cleared — exemplar memory survives re-indexing
   * (this method runs on every ask via retrieveSchema). Hidden columns are
   * excluded from documents entirely — they never reach prompt-level surfaces.
   */
  public void index(UUID dataSourceId, SchemaSnapshot snapshot) {
    FilterExpressionBuilder filters = new FilterExpressionBuilder();
    vectorStore.delete(kindFilter(dataSourceId, KIND_TABLE).build());
    vectorStore.delete(kindFilter(dataSourceId, KIND_COLUMN).build());
    if (snapshot == null || snapshot.tables() == null || snapshot.tables().isEmpty()) {
      return;
    }

    List<Document> documents = new ArrayList<>();
    for (SchemaSnapshot.SchemaTable table : snapshot.tables()) {
      documents.add(tableDoc(dataSourceId, table));
      if (table.columns() != null) {
        for (SchemaSnapshot.SchemaColumn column : table.columns()) {
          if (!column.hidden()) {
            documents.add(columnDoc(dataSourceId, table.name(), column));
          }
        }
      }
    }
    vectorStore.add(documents);
  }

  /**
   * Retrieve the most relevant schema doc lines for a question, scoped to the
   * data source. Empty question or empty index → empty list.
   *
   * Runs one search per schema-doc kind: pgvector's filter converter chokes on
   * OR combinations (full filter silently dropped), so table and column kinds
   * are queried separately instead of a single `kind IN (...)` expression.
   */
  public List<String> search(UUID dataSourceId, String question, int topK) {
    if (question == null || question.isBlank()) {
      return List.of();
    }
    List<Document> hits = new ArrayList<>();
    for (String kind : List.of(KIND_TABLE, KIND_COLUMN)) {
      hits.addAll(vectorStore.similaritySearch(SearchRequest.builder()
          .query(question)
          .topK(topK)
          .similarityThreshold(0.2)
          .filterExpression(kindFilter(dataSourceId, kind).build())
          .build()));
    }
    return hits.stream()
        .map(Document::getText)
        .toList();
  }

  /**
   * Memorize a completed question→SQL pair for a data source. The normalized
   * question determines the document id, so memorizing the same question
   * again updates its SQL in place (no duplicate exemplars).
   */
  public void memorize(UUID dataSourceId, String question, String sql) {
    if (question == null || question.isBlank() || sql == null || sql.isBlank()) {
      return;
    }
    String trimmedQuestion = question.strip();
    String exemplarId = "ds-" + dataSourceId + "-exemplar-"
        + UUID.nameUUIDFromBytes(trimmedQuestion
            .toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    vectorStore.add(List.of(Document.builder()
        .id(exemplarId)
        .text(trimmedQuestion)
        .metadata(META_DATASOURCE_ID, dataSourceId.toString())
        .metadata(META_KIND, KIND_EXEMPLAR)
        .metadata(META_SQL, sql)
        .build()));
  }

  /**
   * Retrieve the most similar past answered questions for a question, scoped
   * to the data source. Only true exemplars (SQL attached) are returned.
   */
  public List<JobExemplar> searchExemplars(UUID dataSourceId, String question, int topK) {
    if (question == null || question.isBlank()) {
      return List.of();
    }
    FilterExpressionBuilder filters = new FilterExpressionBuilder();
    List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
        .query(question)
        .topK(topK)
        .similarityThreshold(0.2)
        .filterExpression(kindFilter(dataSourceId, KIND_EXEMPLAR).build())
        .build());
    return hits.stream()
        .map(hit -> new JobExemplar(hit.getText(),
            String.valueOf(hit.getMetadata().get(META_SQL))))
        .toList();
  }

  /** Removes every document (schema docs AND exemplars) for a data source. */
  public void evict(UUID dataSourceId) {
    vectorStore.delete(scopeFilter(dataSourceId).build());
  }

  private FilterExpressionBuilder.Op scopeFilter(UUID dataSourceId) {
    return new FilterExpressionBuilder()
        .eq(META_DATASOURCE_ID, dataSourceId.toString());
  }

  private FilterExpressionBuilder.Op kindFilter(UUID dataSourceId, String kind) {
    return new FilterExpressionBuilder().and(
        new FilterExpressionBuilder().eq(META_DATASOURCE_ID, dataSourceId.toString()),
        new FilterExpressionBuilder().eq(META_KIND, kind));
  }

  private Document tableDoc(UUID dataSourceId, SchemaSnapshot.SchemaTable table) {
    StringBuilder text = new StringBuilder()
        .append("Table ").append(table.name())
        .append(": columns ")
        .append(formatColumns(table));
    if (table.primaryKeys() != null && !table.primaryKeys().isEmpty()) {
      text.append("; primary keys ").append(String.join(", ", table.primaryKeys()));
    }
    if (table.foreignKeys() != null) {
      for (SchemaSnapshot.ForeignKey fk : table.foreignKeys()) {
        text.append("; references ").append(fk.referencedTable())
            .append('(').append(String.join(", ", fk.columns())).append(')');
      }
    }
    return Document.builder()
        .id("ds-" + dataSourceId + "-" + KIND_TABLE + "-" + table.name())
        .text(text.toString())
        .metadata(META_DATASOURCE_ID, dataSourceId.toString())
        .metadata(META_KIND, KIND_TABLE)
        .metadata(META_TABLE, table.name())
        .build();
  }

  private Document columnDoc(UUID dataSourceId, String tableName,
      SchemaSnapshot.SchemaColumn column) {
    return Document.builder()
        .id("ds-" + dataSourceId + "-" + KIND_COLUMN + "-" + tableName + "." + column.name())
        .text("Table " + tableName + " column " + column.name() + " (type "
            + column.type() + ", nullable=" + column.nullable() + ")")
        .metadata(META_DATASOURCE_ID, dataSourceId.toString())
        .metadata(META_KIND, KIND_COLUMN)
        .metadata(META_TABLE, tableName + "." + column.name())
        .build();
  }

  private String formatColumns(SchemaSnapshot.SchemaTable table) {
    if (table.columns() == null) {
      return "(none)";
    }
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (SchemaSnapshot.SchemaColumn column : table.columns()) {
      if (column.hidden()) {
        continue;
      }
      if (!first) {
        sb.append(", ");
      }
      sb.append(column.name()).append(' ').append(column.type());
      first = false;
    }
    return first ? "(none)" : sb.toString();
  }
}
