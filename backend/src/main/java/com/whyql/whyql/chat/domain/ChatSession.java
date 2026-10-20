package com.whyql.whyql.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * A named conversation of question/answer exchanges. Sessions without a data
 * source are allowed (general questions); {@code title} stays NULL until the
 * pipeline derives it from the first user message.
 */
@Entity
@Table(name = "chat_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

  @Id
  @GeneratedValue
  private UUID id;

  private String ownerUserId;

  private String title;

  private UUID dataSourceId;

  private Instant createdAt = Instant.now();

  private Instant updatedAt = Instant.now();
}
