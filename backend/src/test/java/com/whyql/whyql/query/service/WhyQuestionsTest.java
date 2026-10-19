package com.whyql.whyql.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure matcher tests for the why-question detector — no Spring context. */
class WhyQuestionsTest {

  @Test
  void detectsLeadingExplanationIntent() {
    assertThat(WhyQuestions.classify(
        "why are these people not in userprofile")).isTrue();
    assertThat(WhyQuestions.classify("Why did sales drop?")).isTrue();
    assertThat(WhyQuestions.classify("how come the numbers doubled")).isTrue();
    assertThat(WhyQuestions.classify("what caused the spike in july")).isTrue();
    assertThat(WhyQuestions.classify("EXPLAIN WHY users are missing profiles")).isTrue();
    assertThat(WhyQuestions.classify("reason for the duplicate orders")).isTrue();
    assertThat(WhyQuestions.classify(
        "what is the reason that total profiles are 31 but total users are 37"))
        .isTrue();
    assertThat(WhyQuestions.classify("what are the reasons behind the gap")).isTrue();
    assertThat(WhyQuestions.classify(
        "explain the difference between total users and total profiles")).isTrue();
  }

  @Test
  void dataQuestionsThatMentionWhyAreNotNarrative() {
    assertThat(WhyQuestions.classify(
        "list the people in total users who are not in user profile")).isFalse();
    assertThat(WhyQuestions.classify("show orders and the reason column")).isFalse();
    assertThat(WhyQuestions.classify("count users grouped by reason code")).isFalse();
    assertThat(WhyQuestions.classify("")).isFalse();
    assertThat(WhyQuestions.classify(null)).isFalse();
  }
}
