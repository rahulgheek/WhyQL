package com.whyql.whyql.query.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.domain.SchemaSnapshot;

class PromptBuilderTest {

  private final PromptBuilder builder = new PromptBuilder();

  private static final SchemaSnapshot SNAPSHOT = new SchemaSnapshot(
      "test", List.of(new SchemaSnapshot.SchemaTable(
          "customers",
          List.of(new SchemaSnapshot.SchemaColumn("id", "BIGINT", false, false),
              new SchemaSnapshot.SchemaColumn("name", "TEXT", true, false)),
          List.of("id"), List.of())),
      Instant.now());

  @Test
  void relevantContextSectionRenderedWhenPresent() {
    PromptBuilder.PromptPair pair = builder.build(
        SNAPSHOT, List.of(), "PostgreSQL", List.of(),  "list customers",
        List.of("Table orders: columns amount DECIMAL"), List.of());

    assertThat(pair.userPrompt()).contains("RELEVANT SCHEMA CONTEXT");
    assertThat(pair.userPrompt()).contains("- Table orders: columns amount DECIMAL");
    // Schema snapshot is still included alongside the retrieved notes.
    assertThat(pair.userPrompt()).contains("customers");
  }

  @Test
  void relevantContextSectionOmittedWhenEmpty() {
    PromptBuilder.PromptPair pair = builder.build(
        SNAPSHOT, List.of(), "PostgreSQL", List.of(),  "list customers", List.of(), List.of());

    assertThat(pair.userPrompt()).doesNotContain("RELEVANT SCHEMA CONTEXT");
    assertThat(pair.userPrompt()).contains("SCHEMA:");
  }

  @Test
  void exemplarSectionRenderedWhenPresent() {
    PromptBuilder.PromptPair pair = builder.build(
        SNAPSHOT, List.of(), "PostgreSQL", List.of(),  "monthly revenue",
        List.of(),
        List.of("asked: \"monthly revenue\" solution: SELECT sum(amount) FROM orders"));

    assertThat(pair.userPrompt()).contains("SIMILAR PAST QUESTIONS");
    assertThat(pair.userPrompt()).contains("SELECT sum(amount) FROM orders");
    assertThat(pair.userPrompt()).contains("SCHEMA:");
  }

  @Test
  void exemplarSectionOmittedWhenEmpty() {
    PromptBuilder.PromptPair pair = builder.build(
        SNAPSHOT, List.of(), "PostgreSQL", List.of(),  "anything", List.of(), List.of());

    assertThat(pair.userPrompt()).doesNotContain("SIMILAR PAST QUESTIONS");
  }
}
