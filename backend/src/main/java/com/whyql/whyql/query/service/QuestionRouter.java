package com.whyql.whyql.query.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.whyql.whyql.query.QueryService;

/**
 * The question router. One small LLM call per question deciding the path,
 * replacing hand-written phrase matching — users phrase the same intent a
 * thousand ways, so classification is the model's job, not a regex's.
 *
 * <p>Failure policy: any problem (LLM down, unparseable reply) answers
 * {@link QueryService#ROUTE_DATA_QUERY} — the question takes the normal
 * SQL pipeline, which either succeeds or reports a friendly error. A router
 * outage must never break the product.
 */
@Component
public class QuestionRouter {

  /** Anything answerable by reading rows: lists, counts, sums, comparisons. */
  private static final Pattern ROUTE_PATTERN =
      Pattern.compile("\\b(DATA_QUERY|QUERY_AND_EXPLAIN|GENERAL)\\b");

  private final ChatClient chatClient;

  public QuestionRouter(ChatClient chatClient) {
    this.chatClient = chatClient;
  }

  /** The route decision; never null, never throws. */
  public String route(String question) {
    try {
      String reply = chatClient.prompt()
          .system(SYSTEM)
          .user(question == null ? "" : question)
          .call()
          .content();
      if (reply == null) {
        return QueryService.ROUTE_DATA_QUERY;
      }
      Matcher matcher = ROUTE_PATTERN.matcher(reply.toUpperCase());
      String found = matcher.find() ? matcher.group(1) : null;
      return found != null ? found : QueryService.ROUTE_DATA_QUERY;
    } catch (Exception e) {
      return QueryService.ROUTE_DATA_QUERY;
    }
  }

  private static final String SYSTEM = """
      You route user questions for a database-asking product. Pick exactly one route.

      DATA_QUERY — the answer lives in the user's database rows (counts, lists, \
      sums, averages, comparisons of their tables).
      QUERY_AND_EXPLAIN — the user asks WHY/HOW about a data situation (a \
      discrepancy, a cause, a reason). SQL gathers evidence first, then an \
      analysis stage reasons over it. Also use for questions that ask for a \
      reason with numbers quoted ("what is the reason that X is 31 but Y is 37").
      GENERAL — general knowledge, definitions, product help, greetings — \
      anything NOT answerable from this user's database.

      Answer with JSON only: {"route":"DATA_QUERY"} / {"route":"QUERY_AND_EXPLAIN"} \
      / {"route":"GENERAL"}
      """;
}
