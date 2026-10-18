package com.whyql.whyql.rag.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Local, deterministic, hash-based lexical embedding model (feature hashing
 * + TF weights + character trigrams).
 *
 * Rationale: Groq exposes no embeddings endpoint (verified — see
 * application.yaml {@code spring.ai.model.embedding: none}), so Phase 8 ships
 * an in-process embedder with no network dependency: it is instant, free, and
 * fully reproducible, which is exactly what schema-doc retrieval needs. The
 * vectors capture lexical overlap (word + trigram features), so a question
 * about "orders" matches the orders table's documents strongly. Synonym-level
 * semantic matching ("sales" vs "orders") is a known limitation — swapping in
 * a real embedding model later only requires replacing this bean.
 */
public final class LexicalEmbeddingModel implements EmbeddingModel {

  public static final int DIMENSIONS = 512;

  /** Function words that carry no retrieval signal for questions or DDL. */
  private static final Set<String> STOPWORDS = Set.of(
      "a", "an", "the", "and", "or", "of", "for", "is", "are", "was", "were",
      "to", "in", "on", "with", "by", "at", "from", "as", "it", "that", "this",
      "be", "which", "what", "how", "show", "me", "list", "all", "many", "per",
      "over", "there", "their", "each", "every", "who", "when", "where", "can",
      "did", "does", "has", "have", "had", "not", "no", "yes", "my", "our",
      "we", "us", "its", "his", "her", "him", "you", "your", "them", "than",
      "then", "so", "if", "but", "into", "about", "between", "some", "any",
      "would", "should", "could", "will", "shall", "do", "get", "give", "given");

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<String> instructions = request.getInstructions();
    List<Embedding> embeddings = new ArrayList<>(instructions.size());
    for (int i = 0; i < instructions.size(); i++) {
      embeddings.add(new Embedding(embed(instructions.get(i)), i));
    }
    return new EmbeddingResponse(embeddings);
  }

  @Override
  public float[] embed(Document document) {
    return embed(document.getText());
  }

  @Override
  public float[] embed(String text) {
    float[] vector = new float[DIMENSIONS];
    if (text == null || text.isBlank()) {
      return vector;
    }

    // Token frequency, plus char trigrams of longer words so substring tokens
    // (e.g. "sold" vs "solds") still share features.
    Map<String, Integer> features = new HashMap<>();
    for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
      String token = raw.replace("'", "");
      if (token.isEmpty()) {
        continue;
      }
      boolean stopword = STOPWORDS.contains(token);
      if (token.length() >= 4) {
        for (int i = 0; i <= token.length() - 3; i++) {
          features.merge(token.substring(i, i + 3), 1, Integer::sum);
        }
        if (!stopword) {
          features.merge(token, 2, Integer::sum);
        }
      } else if (!stopword) {
        features.merge(token, 2, Integer::sum);
      }
    }

    for (Map.Entry<String, Integer> feature : features.entrySet()) {
      double weight = Math.log(1 + feature.getValue());
      int hash = fnv1a(feature.getKey());
      int bucket = Math.abs(hash) % DIMENSIONS;
      vector[bucket] += ((hash & 0x100) != 0) ? -weight : weight;
    }

    double norm = 0;
    for (float component : vector) {
      norm += component * component;
    }
    if (norm > 0) {
      double scale = 1 / Math.sqrt(norm);
      for (int i = 0; i < DIMENSIONS; i++) {
        vector[i] = (float) (vector[i] * scale);
      }
    }
    return vector;
  }

  /** 32-bit FNV-1a: stable word → signed feature-hash mapping. */
  private static int fnv1a(String token) {
    int hash = 0x811c9dc5;
    for (int i = 0; i < token.length(); i++) {
      hash ^= token.charAt(i);
      hash *= 16777619;
    }
    return hash;
  }
}
