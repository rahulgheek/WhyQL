package com.whyql.whyql.schema.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.whyql.whyql.schema.domain.SchemaSnapshotRecord;

@Repository
public interface SchemaSnapshotRepository extends JpaRepository<SchemaSnapshotRecord, UUID> {

  Optional<SchemaSnapshotRecord> findByIdAndOwnerUserId(UUID id, String ownerUserId);

  List<SchemaSnapshotRecord> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);

  List<SchemaSnapshotRecord> findByOwnerUserIdAndDataSourceIdOrderByVersionDesc(
      String ownerUserId, UUID dataSourceId);

  void deleteByDataSourceIdAndOwnerUserId(UUID dataSourceId, String ownerUserId);
}
