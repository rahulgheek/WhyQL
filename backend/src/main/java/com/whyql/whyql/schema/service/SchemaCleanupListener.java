package com.whyql.whyql.schema.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.whyql.whyql.datasource.event.DataSourceDeleted;
import com.whyql.whyql.schema.repository.SchemaSnapshotRepository;

/**
 * Cleans up schema state after a data source is deleted: snapshot rows go, and a
 * sandbox schema (SQL-file upload) is dropped so no orphan data lingers in the
 * app database. Runs after the deletion commits.
 */
@Service
public class SchemaCleanupListener {

  private final SchemaSnapshotRepository repository;
  private final SandboxService sandboxService;

  public SchemaCleanupListener(SchemaSnapshotRepository repository, SandboxService sandboxService) {
    this.repository = repository;
    this.sandboxService = sandboxService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onDataSourceDeleted(DataSourceDeleted event) {
    repository.deleteByDataSourceIdAndOwnerUserId(event.dataSourceId(), event.ownerUserId());
    if (event.sandboxSchema() != null) {
      sandboxService.drop(event.sandboxSchema());
    }
  }
}
