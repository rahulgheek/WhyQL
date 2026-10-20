package com.whyql.whyql.datasource.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory rate limiter for credential reveals: a burst of wrong-password
 * guesses against the reveal endpoint should stall, not hammer bcrypt.
 * 5 attempts per 10 minutes per user; successful or failed, every call to
 * {@link #allowed} counts. Resets on restart (acceptable: the bcrypt check
 * is the real gate, this just blunts brute force).
 */
@Component
public class RevealRateLimiter {

  private static final int MAX_ATTEMPTS = 5;
  private static final java.time.Duration WINDOW = java.time.Duration.ofMinutes(10);

  private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

  /** True when the user may attempt a reveal now (the attempt is recorded). */
  public boolean allowed(String ownerUserId) {
    Deque<Instant> stamps = attempts
        .computeIfAbsent(ownerUserId, k -> new ArrayDeque<>());
    Instant cutoff = Instant.now().minus(WINDOW);
    synchronized (stamps) {
      while (!stamps.isEmpty() && stamps.peekFirst().isBefore(cutoff)) {
        stamps.pollFirst();
      }
      if (stamps.size() >= MAX_ATTEMPTS) {
        return false;
      }
      stamps.addLast(Instant.now());
      return true;
    }
  }
}
