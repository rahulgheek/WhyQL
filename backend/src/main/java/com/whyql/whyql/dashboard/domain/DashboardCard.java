package com.whyql.whyql.dashboard.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A pinned job result on the user's dashboard. The question, engine and the
 * COMPLETED job's result JSON are snapshotted at save time, so the card
 * renders even if the job or data source later changes; the source jobId is
 * kept for provenance and repeat saving is idempotent per (owner, job).
 */
@Entity
@Table(name = "dashboard_cards")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardCard {

  @Id
  @GeneratedValue
  private UUID id;

  private String ownerUserId;

  private UUID dataSourceId;

  private UUID jobId;

  private String question;

  private String engine;

  private String resultJson;

  private Instant createdAt;
}
