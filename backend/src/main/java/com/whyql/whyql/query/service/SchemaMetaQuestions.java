package com.whyql.whyql.query.service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Recognizes questions that are about the schema itself rather than the data
 * inside it ("how many tables are there", "list my tables"). These are answered
 * instantly from the schema snapshot instead of being sent to the LLM, which
 * would otherwise stall on them (they are not answerable with SQL against the
 * allowlisted user tables) and degrade into needless clarifying questions.
 *
 * <p>Matching is deliberately narrow: short questions only, explicit table
 * meta intents, so ordinary data questions that merely mention a table ("top
 * products from the orders table") never take the fast path.
 */
public final class SchemaMetaQuestions {

  private SchemaMetaQuestions() {}

  /** Meta questions are short; anything longer is a data question. */
  private static final int MAX_WORDS = 9;

  /** "how many tables are there", "number of tables in the database", "how many tables does it have". */
  private static final Pattern TABLE_COUNT = Pattern.compile(
      "\\b(how many|number of)\\b[^.?!\"]*\\btables?\\b");

  /** "what tables are there", "list all tables", "show the tables", "which tables do I have". */
  private static final Pattern TABLE_LIST = Pattern.compile(
      "\\b(what|which) tables?\\b"
          + "|\\blist\\b[^.?\"]*\\btables?\\b"
          + "|\\bshow\\b[^.?\"]*\\btables?\\b"
          + "|\\btables?\\s+(in|of|from)\\s+(the|this|my|our)\\s+(database|db|schema)");

  /** "how many schemas are there", "number of schemas in the database". */
  private static final Pattern SCHEMA_COUNT = Pattern.compile(
      "\\b(how many|number of)\\b[^.?\"]*\\bschemas?\\b");

  /**
   * "name all schemas in the database", "list the schemas", "what schemas are
   * there", "show my schemas".
   */
  private static final Pattern SCHEMA_LIST = Pattern.compile(
      "\\b(name|list|show|give|what|which)\\b[^.?\"]*\\bschemas?\\b"
          + "|\\b(all|the)\\s+schemas?\\s+(in|of|from)\\b");

  public enum Kind { TABLE_COUNT, TABLE_LIST, SCHEMA_COUNT, SCHEMA_LIST }

  /**
   * Classifies the question: present when it is a schema-meta question about
   * tables or schemas, with the kind of answer wanted. Table intents are
   * checked first because table questions routinely contain the word
   * "schema" ("tables in the schema"). Never throws.
   */
  public static Optional<Kind> classify(String question) {
    if (question == null || question.isBlank()) {
      return Optional.empty();
    }
    String q = question.toLowerCase(Locale.ROOT).trim();
    if (q.split("\\s+").length > MAX_WORDS) {
      return Optional.empty();
    }
    if (!q.matches(".*\\b(tables?|schemas?)\\b.*")) {
      return Optional.empty();
    }
    if (TABLE_COUNT.matcher(q).find()) {
      return Optional.of(Kind.TABLE_COUNT);
    }
    if (TABLE_LIST.matcher(q).find()) {
      return Optional.of(Kind.TABLE_LIST);
    }
    if (SCHEMA_COUNT.matcher(q).find()) {
      return Optional.of(Kind.SCHEMA_COUNT);
    }
    if (SCHEMA_LIST.matcher(q).find()) {
      return Optional.of(Kind.SCHEMA_LIST);
    }
    return Optional.empty();
  }
}
