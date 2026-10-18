package com.whyql.whyql.schema.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * A stored schema snapshot: live introspection, SQL/DDL, zip of models, GitHub
 * repo, or manual definition all converge here as JSON (the datasource module's
 * SchemaSnapshot shape). Versions increment per (owner, data source, name).
 * Schema owned by Flyway V6; Hibernate only validates.
 */
@Entity
@Table(name = "schema_snapshots")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchemaSnapshotRecord {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(nullable = false)
  private String ownerUserId;

  @Column
  private UUID dataSourceId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private int version;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String snapshotJson;

  @Column(nullable = false)
  private String source;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();
}
