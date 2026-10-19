package com.whyql.whyql.board.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One widget on a presentation board. The widget pins its input (manual table,
 * raw validated SQL, or a natural-language question) plus the chart kind, and
 * stores the TabularResult snapshot produced when it last ran — so the board
 * renders exactly what its presenter saw. Failed runs are persisted with
 * status FAILED and the error text; the board never breaks on load.
 * Schema owned by Flyway V9.
 */
@Entity
@Table(name = "board_widgets",
    uniqueConstraints = @UniqueConstraint(columnNames = {"board_id", "ordinal"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BoardWidget {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "board_id", nullable = false)
  private UUID boardId;

  // Denormalized for cheap ownership guards in list/find queries.
  @Column(name = "owner_user_id", nullable = false)
  private String ownerUserId;

  @Column(name = "data_source_id")
  private UUID dataSourceId;

  @Column(nullable = false)
  private String title;

  /** MANUAL | SQL | QUESTION (see BoardService.Mode). */
  @Column(nullable = false, length = 20)
  private String mode;

  @Column(name = "table_name")
  private String tableName;

  @Column(name = "sql_text")
  private String sqlText;

  @Column
  private String question;

  /** TABLE | BAR | LINE | PIE (see BoardService.ChartKind). */
  @Column(name = "chart_kind", nullable = false, length = 20)
  private String chartKind;

  /** Slot on the board, 0..MAX_WIDGETS-1. */
  @Column(nullable = false)
  private int ordinal;

  /** OK | FAILED | NEEDS_CLARIFICATION. */
  @Column(nullable = false, length = 30)
  private String status;

  @Column(name = "error_message")
  private String errorMessage;

  /** TabularResult JSON snapshot of the last successful run. */
  @Column(name = "result_json")
  private String resultJson;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();
}
