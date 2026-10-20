package com.whyql.whyql.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * One message inside a chat session. User rows always carry text; assistant
 * rows carry the analysis text (nullable) and — for data answers — the linked
 * job so the UI can render the rich result instead of plain text.
 */
@Entity
@Table(name = "chat_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

  public static final String ROLE_USER = "USER";
  public static final String ROLE_ASSISTANT = "ASSISTANT";

  @Id
  @GeneratedValue
  private UUID id;

  @ManyToOne
  @JoinColumn(name = "session_id", nullable = false)
  private ChatSession session;

  /** {@link #ROLE_USER} or {@link #ROLE_ASSISTANT}. */
  private String role;

  private String text;

  private UUID jobId;

  private Instant createdAt = Instant.now();
}
