package com.whyql.whyql.api.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.whyql.whyql.api.model.ChatMessage;
import com.whyql.whyql.api.model.ChatMessagePage;
import com.whyql.whyql.api.model.ChatSession;
import com.whyql.whyql.api.model.ChatSessionPage;
import com.whyql.whyql.chat.service.ChatService;

/**
 * Maps chat domain entities onto the GraphQL shapes, resolving message
 * counts for a whole page in one grouped query (no N+1 badges).
 */
@Component
public class ChatMapper {

  private final ChatService chatService;

  public ChatMapper(ChatService chatService) {
    this.chatService = chatService;
  }

  public ChatSession toGql(com.whyql.whyql.chat.domain.ChatSession entity) {
    return new ChatSession(
        entity.getId(),
        entity.getTitle(),
        entity.getDataSourceId(),
        0, // count filled by the page mapper; single-session use passes 0.
        entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
        entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
  }

  public ChatSession toGql(com.whyql.whyql.chat.domain.ChatSession entity, long count) {
    return new ChatSession(
        entity.getId(),
        entity.getTitle(),
        entity.getDataSourceId(),
        (int) count,
        entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
        entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
  }

  public ChatSessionPage toSessionPage(org.springframework.data.domain.Page<com.whyql.whyql.chat.domain.ChatSession> page) {
    List<UUID> ids = page.getContent().stream()
        .map(com.whyql.whyql.chat.domain.ChatSession::getId).toList();
    var counts = chatService.messageCounts(ids);
    List<ChatSession> items = page.getContent().stream()
        .map(s -> toGql(s, counts.getOrDefault(s.getId(), 0L)))
        .toList();
    return new ChatSessionPage(items, page.getTotalElements(), page.getNumber(),
        page.getSize(), page.hasNext());
  }

  public ChatMessage toGql(com.whyql.whyql.chat.domain.ChatMessage entity) {
    return new ChatMessage(
        entity.getId(),
        entity.getRole(),
        entity.getText(),
        entity.getJobId(),
        entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
  }

  /**
   * Reverses the newest-first repository page so items render ascending
   * while {@code page} keeps indexing backwards (0 = newest window).
   */
  public ChatMessagePage toMessagePage(org.springframework.data.domain.Page<com.whyql.whyql.chat.domain.ChatMessage> page) {
    List<ChatMessage> items = new java.util.ArrayList<>(page.getContent().stream()
        .map(this::toGql).toList());
    java.util.Collections.reverse(items);
    return new ChatMessagePage(items, page.getTotalElements(), page.getNumber(),
        page.getSize(), page.hasNext());
  }
}
