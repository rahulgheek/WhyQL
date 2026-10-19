package com.whyql.whyql.query.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognizes explanatory questions ("why are these people not in user
 * profile", "how come sales dropped"). SQL can only supply evidence rows for
 * them — the narrative explanation comes from the analysis stage, prompted
 * with those rows. Matching is deliberately narrow: question-leading
 * why/how phrasing, so data questions that merely contain "why" ("show why
 * failed logins happened" filtered columns) still go the normal SQL way.
 */
public final class WhyQuestions {

  private WhyQuestions() {}

  private static final Pattern LEAD = Pattern.compile(
      "^\\s*(why\\b|how come\\b|explain why\\b)"
          + "|^\\s*what\\s+(is|are|'s)\\s+the\\s+reasons?\\b"
          + "|^\\s*what\\s+(caused|made|causes|makes)\\b");

  /** Explanation-intent phrases anywhere in the question ("…, the reason being…"). */
  private static final Pattern ANYWHERE = Pattern.compile(
      "\\breasons?\\s+(why|that|for|behind)\\b"
          + "|\\bthe\\s+reason\\s+(is|for)\\b"
          + "|\\bexplain\\s+the\\s+(difference|gap|discrepancy|mismatch)\\b");

  /** True when the question carries explanation intent. Never throws. */
  public static boolean classify(String question) {
    if (question == null || question.isBlank()) {
      return false;
    }
    String q = question.toLowerCase(Locale.ROOT).trim();
    return LEAD.matcher(q).find() || ANYWHERE.matcher(q).find();
  }
}
