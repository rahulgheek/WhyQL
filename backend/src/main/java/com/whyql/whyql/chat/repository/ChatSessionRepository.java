package com.whyql.whyql.chat.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.whyql.whyql.chat.domain.ChatSession;

/** Data-access for chat sessions. Internal to the Chat module. */
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

  /** Paged session list for the dashboard; most recently active first. */
  org.springframework.data.domain.Page<ChatSession>
      findByOwnerUserIdOrderByUpdatedAtDesc(String ownerUserId,
          org.springframework.data.domain.Pageable pageable);

  Optional<ChatSession> findByIdAndOwnerUserId(UUID id, String ownerUserId);

  long countByOwnerUserId(String ownerUserId);
}
