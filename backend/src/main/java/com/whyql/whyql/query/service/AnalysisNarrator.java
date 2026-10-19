package com.whyql.whyql.query.service;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.whyql.whyql.datasource.domain.TabularResult;

/**
 * Stage 4 narrator: turns executed result rows into a short, grounded
 * explanation for why-questions. The rows are the ONLY evidence allowed —
 * the model must explain patterns it can see and say plainly when the data
 * does not determine an answer. Failures land in the caller's absorb path;
 * a narrator outage degrades to the plain result table, never a broken job.
 */
@Component
public class AnalysisNarrator {

  /** Evidence budget: the narrative must not prompt with unbounded rows. */
  private static final int MAX_ROWS = 40;
  private static final int MAX_CELL_CHARS = 120;

  private final ChatClient chatClient;

  public AnalysisNarrator(ChatClient chatClient) {
    this.chatClient = chatClient;
  }

  /** Narrative for a why-question over the executed result; never throws. */
  public String narrate(String question, TabularResult result) {
    if (result == null || result.rows() == null || result.rows().isEmpty()) {
      return "The query found no rows, so there is nothing in the data to explain \""
          + question + "\". The blank result itself is the finding — either the rows "
          + "really are absent, or the question needs different evidence (different "
          + "tables or a join this database does not expose).";
    }

    String evidence = renderEvidence(result);
    String system = """
        You explain WHY-questions about database contents using only the evidence rows provided.

        Rules:
        - Ground every claim in the evidence: reference column names, counts, values.
        - Point out the pattern that explains the situation (missing keys, null joins, \
        dates, duplicates, mismatches) when the evidence shows one.
        - Say plainly, in a final short sentence, when the data alone cannot determine \
        the cause and what data would be needed.
        - Never invent facts, never claim access to data that is not in the evidence.
        - At most 120 words, plain prose, no markdown formatting, no tables, no bullet lists.
        """;
    String user = "Question: " + question + "\n\nEvidence rows:\n" + evidence;

    return chatClient.prompt()
        .system(system)
        .user(user)
        .call()
        .content();
  }

  private String renderEvidence(TabularResult result) {
    List<TabularResult.ResultColumn> columns = result.columns();
    StringBuilder sb = new StringBuilder();
    sb.append(columns.stream().map(TabularResult.ResultColumn::name)
        .reduce((a, b) -> a + " | " + b).orElse(""));
    sb.append('\n');
    List<List<Object>> rows = result.rows();
    int count = Math.min(rows.size(), MAX_ROWS);
    if (count < rows.size()) {
      sb.append('(').append(rows.size() - count).append(" more rows omitted)\n");
    }
    for (int i = 0; i < count; i++) {
      List<Object> row = rows.get(i);
      for (int c = 0; c < row.size() && c < columns.size(); c++) {
        String cell = String.valueOf(row.get(c));
        if (cell.length() > MAX_CELL_CHARS) {
          cell = cell.substring(0, MAX_CELL_CHARS) + "…";
        }
        if (c > 0) {
          sb.append(" | ");
        }
        sb.append(cell);
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
