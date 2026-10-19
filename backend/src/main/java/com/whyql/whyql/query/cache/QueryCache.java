package com.whyql.whyql.query.cache;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * In-memory cache for question-to-query results, keyed by the normalized question
 * plus the schema version. Entries expire after 1 hour.
 */
@Component
public class QueryCache {

  private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

  public record CacheEntry(com.whyql.whyql.datasource.domain.QueryPlan plan, long expiryMs) {}

  public com.whyql.whyql.datasource.domain.QueryPlan get(String key) {
    CacheEntry entry = cache.get(key);
    if (entry == null) {
      return null;
    }
    if (System.currentTimeMillis() > entry.expiryMs()) {
      cache.remove(key);
      return null;
    }
    return entry.plan();
  }

  public void put(String key, com.whyql.whyql.datasource.domain.QueryPlan plan, long ttlMinutes) {
    cache.put(key, new CacheEntry(plan,
        System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(ttlMinutes)));
  }

  public void invalidate(String key) {
    cache.remove(key);
  }

  /** Normalize a question for cache key purposes: lowercase, trim, collapse whitespace. */
  public static String normalizeQuestion(String question) {
    return question == null ? "" : question.trim().toLowerCase().replaceAll("\\s+", " ");
  }
}
