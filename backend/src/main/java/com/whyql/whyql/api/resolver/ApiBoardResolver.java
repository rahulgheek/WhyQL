package com.whyql.whyql.api.resolver;

import java.util.List;
import java.util.UUID;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import com.whyql.whyql.api.board.BoardMapper;
import com.whyql.whyql.api.model.Board;
import com.whyql.whyql.api.model.BoardWidget;
import com.whyql.whyql.api.model.BoardWidgetInput;
import com.whyql.whyql.board.service.BoardService;
import com.whyql.whyql.query.QueryService;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;

/**
 * Board resolvers: presentation boards (widget-based visual dashboards, max
 * {@value BoardService#MAX_WIDGETS} widgets). Every mapping is
 * owner-scoped through {@link AuthGuards}. saveBoardWidget runs the widget's
 * input synchronously — questions go through the LLM pipeline, so a slow run
 * surfaces as loading state in the builder, not as a background job.
 */
@Controller
public class ApiBoardResolver {

  private final BoardService boardService;
  private final BoardMapper boardMapper;
  private final QueryService queryService;

  public ApiBoardResolver(BoardService boardService, BoardMapper boardMapper,
      QueryService queryService) {
    this.boardService = boardService;
    this.boardMapper = boardMapper;
    this.queryService = queryService;
  }

  @QueryMapping
  public List<Board> boards(Authentication auth) {
    AuthGuards.requireUser(auth);
    return boardMapper.toGqlList(boardService.listBoards(auth.getName()));
  }

  @QueryMapping
  public Board board(@Argument UUID id, Authentication auth) {
    AuthGuards.requireUser(auth);
    try {
      return boardMapper.toGql(
          boardService.getBoard(auth.getName(), id),
          boardService.listWidgets(auth.getName(), id));
    } catch (IllegalArgumentException notFound) {
      throw new NotFoundGraphQLException(notFound.getMessage());
    }
  }

  /** Table names of one owned data source, for the manual-selection picker. */
  @QueryMapping
  public List<String> schemaTables(@Argument UUID dataSourceId, Authentication auth) {
    AuthGuards.requireUser(auth);
    try {
      SchemaSnapshot snapshot = queryService.retrieveSchema(dataSourceId, auth.getName());
      return snapshot.tables() == null ? List.of() : snapshot.tables().stream()
          .map(SchemaSnapshot.SchemaTable::name)
          .filter(name -> name != null && !name.isBlank())
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .toList();
    } catch (Exception e) {
      throw new NotFoundGraphQLException("Data source not found: " + dataSourceId);
    }
  }

  @MutationMapping
  public Board createBoard(@Argument String title, Authentication auth) {
    AuthGuards.requireUser(auth);
    com.whyql.whyql.board.domain.Board board =
        boardService.createBoard(auth.getName(), title);
    return boardMapper.toGql(board, List.of());
  }

  @MutationMapping
  public boolean removeBoard(@Argument UUID id, Authentication auth) {
    AuthGuards.requireUser(auth);
    try {
      boardService.removeBoard(auth.getName(), id);
      return true;
    } catch (IllegalArgumentException notFound) {
      throw new NotFoundGraphQLException(notFound.getMessage());
    }
  }

  @MutationMapping
  public BoardWidget saveBoardWidget(@Argument UUID boardId,
      @Argument BoardWidgetInput input, Authentication auth) {
    AuthGuards.requireUser(auth);
    BoardService.WidgetSpec spec = new BoardService.WidgetSpec(
        input.id(),
        input.title(),
        input.dataSourceId(),
        input.mode() == null ? null : input.mode().name(),
        input.tableName(),
        input.sqlText(),
        input.question(),
        input.chartKind() == null ? "TABLE" : input.chartKind().name(),
        input.ordinal());
    try {
      com.whyql.whyql.board.domain.BoardWidget widget =
          boardService.saveBoardWidget(auth.getName(), boardId, spec);
      return boardMapper.toGql(widget);
    } catch (IllegalArgumentException bad) {
      throw new NotFoundGraphQLException(bad.getMessage());
    } catch (IllegalStateException conflict) {
      throw new ForbiddenGraphQLException(conflict.getMessage());
    }
  }

  @MutationMapping
  public boolean removeBoardWidget(@Argument UUID boardId, @Argument UUID widgetId,
      Authentication auth) {
    AuthGuards.requireUser(auth);
    if (!boardService.removeWidget(auth.getName(), boardId, widgetId)) {
      throw new NotFoundGraphQLException("Widget not found: " + widgetId);
    }
    return true;
  }
}
