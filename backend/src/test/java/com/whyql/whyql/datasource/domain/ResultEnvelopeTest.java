package com.whyql.whyql.datasource.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * The stored-result contract: sections envelopes and legacy single-table JSON
 * parse into the same shape, garbage fails soft, and oversized parts keep only
 * their count.
 */
class ResultEnvelopeTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void legacySingleResultWrapsAsOneOkSection() {
    TabularResult legacy = new TabularResult(
        List.of(new TabularResult.ResultColumn("n", "int4")),
        List.of(List.of(1L), List.of(2L), List.of(3L)),
        false);
    String json = objectMapper.writeValueAsString(legacy);

    ResultEnvelope envelope = ResultEnvelope.parse(json, objectMapper);

    assertThat(envelope).isNotNull();
    assertThat(envelope.kind()).isNull(); // legacy marker
    assertThat(envelope.sections()).hasSize(1);
    ResultEnvelope.Section section = envelope.sections().get(0);
    assertThat(section.sizeCategory()).isEqualTo(ResultEnvelope.SIZE_OK);
    assertThat(section.result().rows()).hasSize(3);
    assertThat(envelope.primaryResult()).isSameAs(section.result());
  }

  @Test
  void sectionsEnvelopeRoundTrips() {
    ResultEnvelope envelope = new ResultEnvelope(
        ResultEnvelope.KIND_SECTIONS,
        List.of(
            new ResultEnvelope.Section(null, ResultEnvelope.SIZE_OK, 1L,
                new TabularResult(
                    List.of(new TabularResult.ResultColumn("one", "int4")),
                    List.of(List.of(1)), false)),
            new ResultEnvelope.Section("monthly", ResultEnvelope.SIZE_TOO_LARGE,
                987_654L, null)));
    String json = objectMapper.writeValueAsString(envelope);

    ResultEnvelope parsed = ResultEnvelope.parse(json, objectMapper);

    assertThat(parsed).isEqualTo(envelope);
    assertThat(parsed.primaryResult()).isNotNull();
    // Oversized part carries only its count.
    assertThat(parsed.sections().get(1).result()).isNull();
    assertThat(parsed.sections().get(1).totalRows()).isEqualTo(987_654L);
  }

  @Test
  void garbageAndMissingJsonFailSoft() {
    assertThat(ResultEnvelope.parse(null, objectMapper)).isNull();
    assertThat(ResultEnvelope.parse("", objectMapper)).isNull();
    assertThat(ResultEnvelope.parse("not json {", objectMapper)).isNull();
    assertThat(ResultEnvelope.parse("[]", objectMapper)).isNull();
    assertThat(ResultEnvelope.parse("null", objectMapper)).isNull();
  }
}
