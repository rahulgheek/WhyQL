package com.whyql.whyql.rag.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.whyql.whyql.datasource.DataSourceDeleted;

/**
 * Drops the data source's complete vector space (schema docs AND exemplar
 * memory) when the data source is deleted, mirroring the schema module's
 * cleanup — no orphan embeddings linger in the app database.
 */
@Service
public class RagCleanupListener {

  private final SchemaDocsService schemaDocs;

  public RagCleanupListener(SchemaDocsService schemaDocs) {
    this.schemaDocs = schemaDocs;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDataSourceDeleted(DataSourceDeleted event) {
    schemaDocs.evict(event.dataSourceId());
  }
}
