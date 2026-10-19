package com.whyql.whyql.api.board;

import java.util.List;

import org.springframework.stereotype.Component;

import com.whyql.whyql.api.model.Board;
import com.whyql.whyql.api.model.BoardWidget;
import com.whyql.whyql.api.model.QueryResult;
import com.whyql.whyql.datasource.domain.TabularResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps board entities to the GraphQL {@code Board}/{@code BoardWidget} shapes,
 * parsing the snapshotted result JSON. A corrupted snapshot renders an empty
 * card instead of failing the whole board load.
 */
@Component
public class BoardMapper {

  private final ObjectMapper objectMapper;

  public BoardMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** List view: boards without their widgets (cheap board switcher). */
  public List<Board> toGqlList(List<com.whyql.whyql.board.domain.Board> boards) {
    return boards.stream()
        .map(board -> new Board(board.getId(), board.getTitle(), List.of(),
            board.getCreatedAt().toString()))
        .toList();
  }

  public Board toGql(com.whyql.whyql.board.domain.Board board,
      List<com.whyql.whyql.board.domain.BoardWidget> widgets) {
    return new Board(
        board.getId(),
        board.getTitle(),
        widgets.stream().map(this::toGql).toList(),
        board.getCreatedAt().toString());
  }

  public BoardWidget toGql(com.whyql.whyql.board.domain.BoardWidget widget) {
    return new BoardWidget(
        widget.getId(),
        widget.getBoardId(),
        widget.getTitle(),
        widget.getDataSourceId(),
        Board.WidgetMode.valueOf(widget.getMode()),
        widget.getTableName(),
        widget.getSqlText(),
        widget.getQuestion(),
        Board.ChartKind.valueOf(widget.getChartKind()),
        widget.getOrdinal(),
        widget.getStatus(),
        widget.getErrorMessage(),
        toResult(widget.getResultJson()),
        widget.getCreatedAt() == null ? null : widget.getCreatedAt().toString(),
        widget.getUpdatedAt() == null ? null : widget.getUpdatedAt().toString());
  }

  private QueryResult toResult(String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      TabularResult parsed = objectMapper.readValue(resultJson, TabularResult.class);
      return QueryResult.from(parsed);
    } catch (Exception e) {
      return null;
    }
  }
}
