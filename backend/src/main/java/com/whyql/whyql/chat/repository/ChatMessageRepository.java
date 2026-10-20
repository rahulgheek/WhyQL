package com.whyql.whyql.chat.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.whyql.whyql.chat.domain.ChatMessage;

/** Data-access for chat messages. Internal to the Chat module. */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

  /**
   * Newest-first paging lets the server hand back only the latest window;
   * callers reverse each page for chronological display.
   */
  Page<ChatMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

  long countBySessionId(UUID sessionId);

  /** true when this session already stored its first user message. */
  boolean existsBySessionIdAndRole(UUID sessionId, String role);

  /** One grouped query for a whole page of session badges: id -> count. */
  @org.springframework.data.jpa.repository.Query(
      "select m.session.id, count(m) from ChatMessage m "
          + "where m.session.id in :ids group by m.session.id")
  java.util.List<Object[]> countBySessionIdIn(
      @org.springframework.data.repository.query.Param("ids")
      java.util.Collection<UUID> ids);
}
