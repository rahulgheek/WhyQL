package com.whyql.whyql.chat.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.whyql.whyql.chat.domain.ChatMessage;
import com.whyql.whyql.chat.domain.ChatSession;
import com.whyql.whyql.chat.repository.ChatMessageRepository;
import com.whyql.whyql.chat.repository.ChatSessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for chat persistence: ownership enforcement on reads
 * and mutations, pipeline recording that absorbs foreign/missing sessions,
 * and title auto-derivation from the first user message.
 */
class ChatServiceTest {

  private ChatSessionRepository sessions;
  private ChatMessageRepository messages;
  private ChatService chatService;

  private ChatSession session;

  @BeforeEach
  void setUp() {
    sessions = Mockito.mock(ChatSessionRepository.class);
    messages = Mockito.mock(ChatMessageRepository.class);
    chatService = new ChatService(sessions, messages);

    session = new ChatSession();
    session.setId(UUID.randomUUID());
    session.setOwnerUserId("user-1");
  }

  @Test
  void createStoresOwnerTitleAndSource() {
    when(sessions.save(any(ChatSession.class))).thenAnswer(inv -> {
      ChatSession arg = inv.getArgument(0);
      arg.setId(UUID.randomUUID());
      return arg;
    });

    ChatSession created = chatService.create("user-1", "Revenue thread",
        UUID.randomUUID());

    assertEquals("Revenue thread", created.getTitle());
    assertEquals("user-1", created.getOwnerUserId());
    verify(sessions).save(created);
  }

  @Test
  void createWithBlankTitleStaysNullForAutoDerivation() {
    when(sessions.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

    ChatSession created = chatService.create("user-1", "   ", null);

    assertNull(created.getTitle());
  }

  @Test
  void ownedRejectsForeignSession() {
    when(sessions.findByIdAndOwnerUserId(any(UUID.class), eq("user-1")))
        .thenReturn(Optional.empty());

    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> chatService.owned("user-1", UUID.randomUUID()));
  }

  @Test
  void renameRequiresOwnership() {
    when(sessions.findByIdAndOwnerUserId(session.getId(), "user-1"))
        .thenReturn(Optional.of(session));
    when(sessions.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));

    chatService.rename("user-1", session.getId(), "New title");

    assertEquals("New title", session.getTitle());
  }

  @Test
  void deleteRequiresOwnership() {
    when(sessions.findByIdAndOwnerUserId(session.getId(), "user-1"))
        .thenReturn(Optional.of(session));

    chatService.delete("user-1", session.getId());

    verify(sessions).delete(session);
  }

  @Test
  void recordUserMessageSavesAndAutoTitlesEmptySession() {
    when(sessions.findByIdAndOwnerUserId(session.getId(), "user-1"))
        .thenReturn(Optional.of(session));
    when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

    UUID jobId = UUID.randomUUID();
    chatService.recordUserMessage(session.getId(), "user-1", "top 5 customers",
        jobId);

    verify(messages).save(any(ChatMessage.class));
    assertEquals("top 5 customers", session.getTitle());
  }

  @Test
  void recordUserMessageKeepsExistingTitle() {
    session.setTitle("Named thread");
    when(sessions.findByIdAndOwnerUserId(session.getId(), "user-1"))
        .thenReturn(Optional.of(session));

    chatService.recordUserMessage(session.getId(), "user-1", "another question", null);

    assertEquals("Named thread", session.getTitle());
  }

  @Test
  void recordUserMessageAbsorbsForeignSessionSilently() {
    when(sessions.findByIdAndOwnerUserId(any(UUID.class), any())).thenReturn(
        Optional.empty());

    // Must not throw: a stale session id never fails the ask pipeline.
    chatService.recordUserMessage(UUID.randomUUID(), "user-1", "q", UUID.randomUUID());

    verify(messages, never()).save(any(ChatMessage.class));
  }

  @Test
  void recordUserMessageIgnoresNullSessionIds() {
    chatService.recordUserMessage(null, "user-1", "q", null);

    verify(messages, never()).save(any(ChatMessage.class));
  }

  @Test
  void recordAssistantMessageStoresTextAndJob() {
    when(sessions.findByIdAndOwnerUserId(session.getId(), "user-1"))
        .thenReturn(Optional.of(session));
    when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

    chatService.recordAssistantMessage(session.getId(), "user-1", "explanation",
        UUID.randomUUID());

    verify(messages).save(any(ChatMessage.class));
  }

  @Test
  void sessionsPageAsksForMostRecentlyActiveFirst() {
    when(sessions.findByOwnerUserIdOrderByUpdatedAtDesc(eq("user-1"),
        any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(session)));

    var page = chatService.sessionsPage("user-1", 0, 10);

    assertEquals(1, page.getTotalElements());
    verify(sessions).findByOwnerUserIdOrderByUpdatedAtDesc(eq("user-1"), any());
  }

  @Test
  void messagesPageCapKeepsSizeBounded() {
    when(messages.findBySessionIdOrderByCreatedAtDesc(eq(session.getId()),
        any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

    // size 500 would hammer the server; the service clamps to 100.
    chatService.messagesPageBidirectional(session.getId(), 0, 500);

    verify(messages).findBySessionIdOrderByCreatedAtDesc(eq(session.getId()),
        eq(PageRequest.of(0, 100)));
  }
}
