package com.whyql.whyql.chat.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whyql.whyql.chat.domain.ChatMessage;
import com.whyql.whyql.chat.domain.ChatSession;
import com.whyql.whyql.chat.repository.ChatMessageRepository;
import com.whyql.whyql.chat.repository.ChatSessionRepository;

/**
 * Chat session bookkeeping for the dashboard and the ask page.
 *
 * Two contracts matter for the rest of the system:
 * <ul>
 * <li><b>Pipeline recording must never fail an ask.</b> {@link #recordUserMessage}
 * and {@link #recordAssistantMessage} absorb unknown/stale/foreign session
 * references silently — a bad id is a logging issue, not a job failure.</li>
 * <li><b>Server load stays bounded.</b> Every read is paged; {@code chatMessages}
 * pages backwards (page 0 = newest window) so the ask page renders the latest
 * exchanges and prepends older pages on scroll.</li>
 * </ul>
 */
@Service
public class ChatService {

  /** Matches the VARCHAR(160) title column; derived titles trim here. */
  private static final int TITLE_MAX = 160;

  private final ChatSessionRepository sessions;
  private final ChatMessageRepository messages;

  public ChatService(ChatSessionRepository sessions, ChatMessageRepository messages) {
    this.sessions = sessions;
    this.messages = messages;
  }

  @Transactional
  public ChatSession create(String ownerUserId, String title, UUID dataSourceId) {
    ChatSession session = new ChatSession();
    session.setOwnerUserId(ownerUserId);
    session.setTitle(trimTitle(title));
    session.setDataSourceId(dataSourceId);
    return sessions.save(session);
  }

  @Transactional
  public ChatSession rename(String ownerUserId, UUID sessionId, String title) {
    ChatSession session = owned(ownerUserId, sessionId);
    session.setTitle(trimTitle(title));
    session.setUpdatedAt(Instant.now());
    return sessions.save(session);
  }

  /** Cascades to messages via the FK; job rows only lose their link value. */
  @Transactional
  public void delete(String ownerUserId, UUID sessionId) {
    ChatSession session = owned(ownerUserId, sessionId);
    sessions.delete(session);
  }

  public ChatSession owned(String ownerUserId, UUID sessionId) {
    return sessions.findByIdAndOwnerUserId(sessionId, ownerUserId)
        .orElseThrow(() -> new IllegalArgumentException(
            "Chat session not found: " + sessionId));
  }

  /** Paged sessions for the dashboard, newest activity first. */
  public Page<ChatSession> sessionsPage(String ownerUserId, int page, int size) {
    return sessions.findByOwnerUserIdOrderByUpdatedAtDesc(ownerUserId,
        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
  }

  /** Message count per session id (empty map entries omitted). */
  public Map<UUID, Long> messageCounts(List<UUID> sessionIds) {
    if (sessionIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Long> counts = new HashMap<>();
    for (Object[] row : messages.countBySessionIdIn(sessionIds)) {
      counts.put((UUID) row[0], (Long) row[1]);
    }
    return counts;
  }

  public long messageCount(UUID sessionId) {
    return messages.countBySessionId(sessionId);
  }

  /**
   * Backwards-paged messages: {@code page 0} is the newest window, and items
   * come back ascending within the window so existing chat UIs can prepend
   * each older page above the current ones without re-sorting.
   */
  public Page<ChatMessage> messagesPageBidirectional(UUID sessionId, int page, int size) {
    return messages.findBySessionIdOrderByCreatedAtDesc(sessionId,
        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
  }

  /**
   * Records the user's question as the next message in the session (chattiness:
   * also bumps {@code updatedAt} and auto-titles from the first message).
   * Absorbs unknown/foreign session ids — caller treats this as best-effort.
   */
  @Transactional
  public void recordUserMessage(UUID sessionId, String ownerUserId, String question,
      UUID jobId) {
    if (sessionId == null) {
      return;
    }
    try {
      sessions.findByIdAndOwnerUserId(sessionId, ownerUserId).ifPresent(session -> {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole(ChatMessage.ROLE_USER);
        message.setText(question);
        message.setJobId(jobId);
        messages.save(message);
        if (session.getTitle() == null || session.getTitle().isBlank()) {
          session.setTitle(trimTitle(question));
        }
        session.setUpdatedAt(Instant.now());
        sessions.save(session);
      });
    } catch (RuntimeException ex) {
      // A chat glitch must never fail the question being asked.
      org.slf4j.LoggerFactory.getLogger(ChatService.class)
          .warn("Chat recording skipped for session {}: {}", sessionId, ex.getMessage());
    }
  }

  /**
   * Records the assistant's answer after a job completes. {@code text} may be
   * null (data answers render from the linked job instead). Best-effort.
   */
  @Transactional
  public void recordAssistantMessage(UUID sessionId, String ownerUserId, String text,
      UUID jobId) {
    if (sessionId == null) {
      return;
    }
    try {
      sessions.findByIdAndOwnerUserId(sessionId, ownerUserId).ifPresent(session -> {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole(ChatMessage.ROLE_ASSISTANT);
        message.setText(text);
        message.setJobId(jobId);
        messages.save(message);
        session.setUpdatedAt(Instant.now());
        sessions.save(session);
      });
    } catch (RuntimeException ex) {
      org.slf4j.LoggerFactory.getLogger(ChatService.class)
          .warn("Chat recording skipped for session {}: {}", sessionId, ex.getMessage());
    }
  }

  private String trimTitle(String title) {
    if (title == null || title.isBlank()) {
      return null;
    }
    String trimmed = title.trim();
    return trimmed.length() > TITLE_MAX ? trimmed.substring(0, TITLE_MAX) : trimmed;
  }
}
