/**
 * Chat: durable question/answer conversations. The pipeline records user and
 * assistant messages automatically; the dashboard lists sessions page by page
 * so no surface ever loads an unbounded history.
 *
 * Cross-module surface: {@code services} (ChatService). Recording calls are
 * best-effort by contract — they absorb unknown or foreign session references
 * and never fail a running job.
 */
@ApplicationModule(displayName = "Chat")
package com.whyql.whyql.chat;

import org.springframework.modulith.ApplicationModule;
