package com.whyql.whyql.board.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whyql.whyql.board.domain.Board;
import com.whyql.whyql.board.domain.BoardWidget;
import com.whyql.whyql.board.repository.BoardRepository;
import com.whyql.whyql.board.repository.BoardWidgetRepository;
import com.whyql.whyql.datasource.domain.DataSourceView;
import com.whyql.whyql.datasource.service.DatasourceGateway;
import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.query.QueryService;
import tools.jackson.databind.ObjectMapper;

/**
 * Presentation boards of up to {@value #MAX_WIDGETS} widgets. A widget pins one
 * of three inputs and re-runs it on save:
 * <ul>
 * <li>{@code MANUAL} — a table picked from the data source's schema; runs
 * {@code SELECT * FROM <table> LIMIT 100}.</li>
 * <li>{@code SQL} — a raw SELECT; must carry (or get appended) a LIMIT and
 * passes through the same validator the NL pipeline uses</li>
 * <li>{@code QUESTION} — a natural-language prompt; runs the synchronous
 * generate -> validate -> execute stages of the query pipeline</li>
 * </ul>
 * Every widget access is owner-scoped. Failed runs are persisted (status
 * FAILED + message) so a board still loads after a bad prompt or a removed
 * data source. Execution flows exclusively through the query module's root
 * {@link QueryService} and the datasource module root types, keeping the
 * Spring Modulith boundaries intact.
 */
@Service
public class BoardService {

  public static final int MAX_WIDGETS = 6;
  public static final String MODE_MANUAL = "MANUAL";
  public static final String MODE_SQL = "SQL";
  public static final String MODE_QUESTION = "QUESTION";
  public static final String STATUS_OK = "OK";
  public static final String STATUS_FAILED = "FAILED";
  public static final String STATUS_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";

  /** A bare SQL identifier; manual table picks must match this. */
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  /** Row cap for every widget run. */
  private static final int WIDGET_ROW_LIMIT = 100;
  /** Error text cap so a garbled stack never floods a widget card. */
  private static final int MAX_ERROR_LENGTH = 500;

  private final BoardRepository boardRepository;
  private final BoardWidgetRepository widgetRepository;
  private final DatasourceGateway dataSourceGateway;
  private final QueryService queryService;
  private final ObjectMapper objectMapper;

  public BoardService(BoardRepository boardRepository, BoardWidgetRepository widgetRepository,
      DatasourceGateway dataSourceGateway, QueryService queryService, ObjectMapper objectMapper) {
    this.boardRepository = boardRepository;
    this.widgetRepository = widgetRepository;
    this.dataSourceGateway = dataSourceGateway;
    this.queryService = queryService;
    this.objectMapper = objectMapper;
  }

  /** One widget's input, normalized before execution. */
  public record WidgetSpec(
      UUID id,
      String title,
      UUID dataSourceId,
      String mode,
      String tableName,
      String sqlText,
      String question,
      String chartKind,
      int ordinal) {}

  @Transactional
  public Board createBoard(String ownerUserId, String title) {
    String normalized = title == null || title.isBlank() ? "Untitled board" : title.trim();
    Board board = new Board();
    board.setOwnerUserId(ownerUserId);
    board.setTitle(normalized);
    return boardRepository.save(board);
  }

  @Transactional(readOnly = true)
  public List<Board> listBoards(String ownerUserId) {
    return boardRepository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId);
  }

  /** The owner's board; throws IllegalArgumentException when missing/foreign. */
  @Transactional(readOnly = true)
  public Board getBoard(String ownerUserId, UUID boardId) {
    return boardRepository.findByIdAndOwnerUserId(boardId, ownerUserId)
        .orElseThrow(() -> new IllegalArgumentException("Board not found: " + boardId));
  }

  /** The owner's board with its widgets in slot order. */
  @Transactional(readOnly = true)
  public List<BoardWidget> listWidgets(String ownerUserId, UUID boardId) {
    getBoard(ownerUserId, boardId);
    return widgetRepository.findByBoardIdAndOwnerUserIdOrderByOrdinalAsc(boardId, ownerUserId);
  }

  @Transactional
  public void removeBoard(String ownerUserId, UUID boardId) {
    getBoard(ownerUserId, boardId);
    widgetRepository.deleteByBoardIdAndOwnerUserId(boardId, ownerUserId);
    boardRepository.findByIdAndOwnerUserId(boardId, ownerUserId)
        .ifPresent(boardRepository::delete);
  }

  /**
   * Creates (when spec.id is null) or updates a widget, runs its input, and
   * returns the persisted widget including the fresh result snapshot. Widget
   * cap of {@value #MAX_WIDGETS} applies to creations only. Throws
   * IllegalArgumentException when the board (or widget id) does not exist,
   * IllegalStateException when the slot is taken or the widget cap is hit.
   */
  @Transactional
  public BoardWidget saveBoardWidget(String ownerUserId, UUID boardId, WidgetSpec spec) {
    Board board = getBoard(ownerUserId, boardId);

    String mode = normalizeMode(spec.mode());
    String chartKind = normalizeChartKind(spec.chartKind());
    if (spec.ordinal() < 0 || spec.ordinal() >= MAX_WIDGETS) {
      throw new IllegalStateException("Widget slot must be between 0 and " + (MAX_WIDGETS - 1));
    }

    BoardWidget widget;
    if (spec.id() != null) {
      widget = widgetRepository.findByIdAndBoardIdAndOwnerUserId(spec.id(), boardId, ownerUserId)
          .orElseThrow(() -> new IllegalArgumentException("Widget not found: " + spec.id()));
      if (widget.getOrdinal() != spec.ordinal()
          && widgetRepository.findByBoardIdAndOwnerUserIdOrderByOrdinalAsc(boardId, ownerUserId)
              .stream().anyMatch(w -> w.getOrdinal() == spec.ordinal() && !w.getId().equals(spec.id()))) {
        throw new IllegalStateException("Slot " + spec.ordinal() + " is already occupied");
      }
    } else {
      long count = widgetRepository.countByBoardIdAndOwnerUserId(boardId, ownerUserId);
      if (count >= MAX_WIDGETS) {
        throw new IllegalStateException(
            "Board widget limit reached (" + MAX_WIDGETS + " widgets per board)");
      }
      if (widgetRepository.findByBoardIdAndOwnerUserIdOrderByOrdinalAsc(boardId, ownerUserId)
          .stream().anyMatch(w -> w.getOrdinal() == spec.ordinal())) {
        throw new IllegalStateException("Slot " + spec.ordinal() + " is already occupied");
      }
      widget = new BoardWidget();
      widget.setBoardId(board.getId());
      widget.setOwnerUserId(ownerUserId);
      widget.setCreatedAt(Instant.now());
      widget.setOrdinal(spec.ordinal());
    }

    widget.setTitle(spec.title() == null || spec.title().isBlank() ? "Widget" : spec.title().trim());
    widget.setDataSourceId(spec.dataSourceId());
    widget.setMode(mode);
    widget.setTableName(spec.tableName());
    widget.setSqlText(spec.sqlText());
    widget.setQuestion(spec.question());
    widget.setChartKind(chartKind);
    widget.setOrdinal(spec.ordinal());
    widget.setStatus(STATUS_FAILED);
    widget.setErrorMessage(null);
    widget.setResultJson(null);
    widget.setUpdatedAt(Instant.now());

    runAndStore(ownerUserId, widget);
    return widgetRepository.save(widget);
  }

  @Transactional
  public boolean removeWidget(String ownerUserId, UUID boardId, UUID widgetId) {
    getBoard(ownerUserId, boardId);
    return widgetRepository.findByIdAndBoardIdAndOwnerUserId(widgetId, boardId, ownerUserId)
        .map(widget -> {
          widgetRepository.delete(widget);
          return true;
        })
        .orElse(false);
  }

  /**
   * Runs the widget's input and writes status + result snapshot onto it.
   * Failures are stored on the widget rather than thrown, so a bad run never
   * blocks editing the rest of the board.
   */
  private void runAndStore(String ownerUserId, BoardWidget widget) {
    try {
      if (widget.getDataSourceId() == null) {
        throw new IllegalArgumentException("Widget needs a data source");
      }
      DataSourceView view = dataSourceGateway.get(widget.getDataSourceId(), ownerUserId);
      SchemaSnapshot snapshot = queryService.retrieveSchema(widget.getDataSourceId(), ownerUserId);

      String sql = switch (widget.getMode()) {
        case MODE_MANUAL -> manualSql(widget.getTableName(), snapshot);
        case MODE_SQL -> userSql(widget.getSqlText());
        case MODE_QUESTION -> {
          QueryPlan generated = queryService.generateQuery(widget.getDataSourceId(),
              ownerUserId, snapshot, widget.getQuestion());
          if (generated.clarifyingQuestions() != null
              && !generated.clarifyingQuestions().isEmpty()) {
            widget.setStatus(STATUS_NEEDS_CLARIFICATION);
            widget.setErrorMessage("Needs clarification: "
                + String.join(" • ", generated.clarifyingQuestions()));
            yield null;
          }
          yield generated.queryText();
        }
        default -> throw new IllegalArgumentException("Unsupported widget mode: " + widget.getMode());
      };

      if (sql == null) {
        return; // question asked for clarification; widget state is already set
      }

      QueryPlan plan = new QueryPlan(view.engine(), sql,
          "board widget: " + widget.getTitle(), List.of(), List.of());
      queryService.validateQuery(plan, snapshot);
      TabularResult result = queryService.executeQuery(widget.getDataSourceId(), ownerUserId, plan);

      widget.setResultJson(objectMapper.writeValueAsString(result));
      widget.setStatus(STATUS_OK);
    } catch (Exception e) {
      widget.setStatus(STATUS_FAILED);
      widget.setErrorMessage(summarize(e.getMessage() == null ? e.toString() : e.getMessage()));
    }
  }

  private static String manualSql(String tableName, SchemaSnapshot snapshot) {
    if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
      throw new IllegalArgumentException("Invalid table name for manual selection: " + tableName);
    }
    boolean known = snapshot.tables() != null && snapshot.tables().stream()
        .anyMatch(t -> t.name() != null && t.name().equalsIgnoreCase(tableName));
    if (!known) {
      throw new IllegalArgumentException("Table not in schema: " + tableName);
    }
    return "SELECT * FROM " + tableName + " LIMIT " + WIDGET_ROW_LIMIT;
  }

  private static String userSql(String sqlText) {
    String sql = sqlText == null ? "" : sqlText.trim();
    if (sql.isEmpty()) {
      throw new IllegalArgumentException("Write a SELECT query for the SQL widget");
    }
    if (!sql.toLowerCase(Locale.ROOT).matches("(?s).*\\blimit\\s+\\d+.*")) {
      sql = sql + " LIMIT " + WIDGET_ROW_LIMIT;
    }
    return sql;
  }

  private static String normalizeMode(String mode) {
    String normalized = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
    if (!List.of(MODE_MANUAL, MODE_SQL, MODE_QUESTION).contains(normalized)) {
      throw new IllegalArgumentException("Unsupported widget mode: " + mode);
    }
    return normalized;
  }

  private static String normalizeChartKind(String chartKind) {
    String normalized = chartKind == null ? "TABLE" : chartKind.trim().toUpperCase(Locale.ROOT);
    if (!List.of("TABLE", "BAR", "LINE", "PIE").contains(normalized)) {
      throw new IllegalArgumentException("Unsupported chart kind: " + chartKind);
    }
    return normalized;
  }

  private static String summarize(String message) {
    return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
  }

  /** Present in Optional only so future callers can skip a re-run cheaply. */
  @Transactional(readOnly = true)
  public Optional<BoardWidget> findWidget(String ownerUserId, UUID boardId, UUID widgetId) {
    return widgetRepository.findByIdAndBoardIdAndOwnerUserId(widgetId, boardId, ownerUserId);
  }
}
