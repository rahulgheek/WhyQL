package com.whyql.whyql.board.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A named presentation board owned by a user. Holds up to
 * {@link com.whyql.whyql.board.service.BoardService#MAX_WIDGETS} widgets
 * (see board_widgets). Schema owned by Flyway V9.
 */
@Entity
@Table(name = "boards")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Board {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "owner_user_id", nullable = false)
  private String ownerUserId;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();
}
