package com.whyql.whyql.query.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.databind.ObjectMapper;
import com.whyql.whyql.datasource.adapter.DataSourceAdapter.AdapterException;
import com.whyql.whyql.datasource.service.DatasourceGateway;
import com.whyql.whyql.datasource.domain.Engine;
import com.whyql.whyql.datasource.domain.EngineDialects;
import com.whyql.whyql.datasource.domain.ExecutionLimits;
import com.whyql.whyql.datasource.domain.QueryPlan;
import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.datasource.domain.TabularResult;
import com.whyql.whyql.datasource.domain.VisibilityRules;
import com.whyql.whyql.query.QueryService;
import com.whyql.whyql.query.cache.QueryCache;
import com.whyql.whyql.query.execution.QueryExecutor;
import com.whyql.whyql.query.prompt.PromptBuilder;
import com.whyql.whyql.query.tool.QueryTools;
import com.whyql.whyql.query.validation.SqlValidator;
import com.whyql.whyql.rag.service.SchemaDocsService;

/** * Synchronous question-to-query pipeline (runs inside one job context).
 *
 * Stages: schema retrieval -> prompt build -> LLM generation -> validation ->
 * execution -> self-correction (max 3 attempts).
 *
 * Individual stage methods are exposed for use by the Kafka-driven async
 * pipeline in the jobs module (Phase 5).
 */
@Service
public class DefaultQueryService implements QueryService {

  private static final Logger log = LoggerFactory.getLogger(DefaultQueryService.class);
  private static final int MAX_SELF_CORRECTION_ATTEMPTS = 3;
  /** Hard cap on optional extra result views per question (see QueryPlan.sections). */
  private static final int MAX_SECTIONS = 3;
  /** The count probe is a single scalar; generous enough for slow warehouses. */
  private static final int COUNT_TIMEOUT_SECONDS = 15;

  private final ChatClient chatClient;
  private final ObjectMapper objectMapper;
  private final DatasourceGateway gateway;
  private final PromptBuilder promptBuilder;
  private final SqlValidator sqlValidator;
  private final QueryExecutor queryExecutor;
  private final QueryCache cache;
  private final QueryTools queryTools;
  private final SchemaDocsService schemaDocs;
  private final CircuitBreaker circuitBreaker;
  private final AnalysisNarrator analysisNarrator;
  private final QuestionRouter questionRouter;

  private static final int RAG_TOP_K = 5;
  private static final int RAG_EXEMPLAR_TOP_K = 3;

  public DefaultQueryService(ChatClient chatClient,
      ObjectMapper objectMapper,
      DatasourceGateway gateway,
      PromptBuilder promptBuilder,
      SqlValidator sqlValidator,
      QueryExecutor queryExecutor,
      QueryCache cache,
      QueryTools queryTools,
      SchemaDocsService schemaDocs,
      CircuitBreaker circuitBreaker,
      AnalysisNarrator analysisNarrator,
      QuestionRouter questionRouter) {
    this.chatClient = chatClient;
    this.objectMapper = objectMapper;
    this.gateway = gateway;
    this.promptBuilder = promptBuilder;
    this.sqlValidator = sqlValidator;
    this.queryExecutor = queryExecutor;
    this.cache = cache;
    this.queryTools = queryTools;
    this.schemaDocs = schemaDocs;
    this.circuitBreaker = circuitBreaker;
    this.analysisNarrator = analysisNarrator;
    this.questionRouter = questionRouter;
  }

  @Override
  public QueryResult askQuestion(QueryRequest request) {
    UUID dataSourceId = request.dataSourceId();
    String question = request.question();
    String ownerUserId = requestOwnerUserId(request);

    // Cache key: normalized question + schema version
    String schemaVersion = gateway.get(dataSourceId, ownerUserId)
        .createdAt().toString();
    String cacheKey = QueryCache.normalizeQuestion(question) + ":" + schemaVersion;

    QueryPlan cached = cache.get(cacheKey);
    if (cached != null) {
      log.info("Cache hit for question: {}", question);
      return executePlan(dataSourceId, ownerUserId, cached);
    }

    // Stage 1: schema retrieval
    SchemaSnapshot snapshot;
    try {
      snapshot = retrieveSchema(dataSourceId, ownerUserId);
    } catch (Exception e) {
      log.error("Schema retrieval failed for dataSource {}", dataSourceId, e);
      return new QueryResult("FAILED", null, null,
          "schema retrieval failed: " + e.getMessage(), List.of());
    }

    // Stage 2: query generation
    QueryPlan plan;
    try {
      plan = generateQuery(dataSourceId, ownerUserId, snapshot, question);
    } catch (Exception e) {
      log.error("Query generation failed for dataSource {}", dataSourceId, e);
      return new QueryResult("FAILED", null, null,
          "query generation failed: " + e.getMessage(), List.of());
    }

    if (plan.clarifyingQuestions() != null && !plan.clarifyingQuestions().isEmpty()) {
      return new QueryResult("NEEDS_CLARIFICATION", null, null, null,
          plan.clarifyingQuestions());
    }

    // Stage 3: validation with self-correction
    QueryPlan validatedPlan;
    try {
      validatedPlan = validateQueryWithRetry(plan, snapshot);
    } catch (Exception e) {
      log.error("Query validation failed for dataSource {}", dataSourceId, e);
      return new QueryResult("FAILED", plan, null,
          "validation failed: " + e.getMessage(), List.of());
    }

    // Stage 4: execution
    TabularResult result;
    try {
      result = executeQuery(dataSourceId, ownerUserId, validatedPlan);
    } catch (Exception e) {
      log.error("Execution failed for dataSource {}", dataSourceId, e);
      return new QueryResult("FAILED", validatedPlan, null,
          "execution failed: " + e.getMessage(), List.of());
    }

    // Cache the successful plan
    cache.put(cacheKey, validatedPlan, 60);

    return new QueryResult("COMPLETED", validatedPlan, result, null, List.of());
  }

  @Override
  public SchemaSnapshot retrieveSchema(UUID dataSourceId, String ownerUserId) {
    SchemaSnapshot snapshot = gateway.introspect(dataSourceId, ownerUserId);
    // Keep the RAG index fresh for this data source. Local embeddings make
    // re-indexing cheap; a failure here must never break the ask itself.
    try {
      schemaDocs.index(dataSourceId, snapshot);
    } catch (Exception e) {
      log.warn("Schema doc indexing failed for data source {}: {}", dataSourceId, e.getMessage());
    }
    return snapshot;
  }

  @Override
  public QueryPlan generateQuery(UUID dataSourceId, String ownerUserId,
      SchemaSnapshot snapshot, String question) {
    // The data source record is the single source of truth for its engine:
    // prompts, plan tags and validation all follow it, per profile.
    Engine engine = gateway.get(dataSourceId, ownerUserId).engine();

    queryTools.setSnapshot(snapshot);

    List<String> relevantContext;
    List<String> exemplarLines;
    try {
      relevantContext = schemaDocs.search(dataSourceId, question, RAG_TOP_K);
      List<SchemaDocsService.JobExemplar> exemplars =
          schemaDocs.searchExemplars(dataSourceId, question, RAG_EXEMPLAR_TOP_K);
      exemplarLines = exemplars.stream()
          .map(exemplar -> "asked: \"" + exemplar.question() + "\" solution: "
              + exemplar.sql())
          .toList();
    } catch (Exception e) {
      log.warn("RAG retrieval failed for data source {}: {}", dataSourceId, e.getMessage());
      relevantContext = List.of();
      exemplarLines = List.of();
    }
    log.info("RAG retrieved {} schema docs and {} exemplars for dataSource {}",
        relevantContext.size(), exemplarLines.size(), dataSourceId);

    PromptBuilder.PromptPair prompts = promptBuilder.build(
        snapshot, List.of(), EngineDialects.displayName(engine),
        EngineDialects.hints(engine), question, relevantContext, exemplarLines);

    String response;
    try {
      // NO toolCallbacks here deliberately: the prompt already carries the full
      // schema, and gpt-oss models on Groq tend to hallucinate tool calls with
      // tools present ("attempted to call tool 'json' which was not in
      // request.tools"), which fails the whole request. QueryTools stays
      // available for future interactive/code-introspection flows.
      response = circuitBreaker.decorateCallable(() -> chatClient.prompt()
          .system(prompts.systemPrompt())
          .user(prompts.userPrompt())
          .call()
          .content()).call();
    } catch (Exception e) {
      throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
    }

    Map<String, Object> parsed = objectMapper.readValue(response, Map.class);
    String queryText = (String) parsed.getOrDefault("queryText", "");
    List<String> assumptions = (List<String>) parsed.getOrDefault("assumptions", List.of());
    List<String> clarifyingQuestions = (List<String>) parsed.getOrDefault(
        "clarifyingQuestions", List.of());
    String explanation = (String) parsed.getOrDefault("explanation", "");
    List<QueryPlan.PlanSection> sections = parseSections(parsed.get("sections"));

    // Contract: queryText is the primary answer, never empty. A model that
    // puts every query in sections and leaves the primary blank gets its
    // first section promoted — the question still gets its answers.
    if (queryText == null || queryText.isBlank()) {
      if (!sections.isEmpty()) {
        QueryPlan.PlanSection first = sections.get(0);
        queryText = first.queryText();
        sections = sections.subList(1, sections.size());
      }
    }

    return new QueryPlan(
        engine,
        queryText,
        explanation,
        assumptions,
        clarifyingQuestions,
        sections);
  }

  /**
   * Reads the LLM's optional extra views. Malformed entries are dropped rather
   * than rejecting the whole plan; a shape that never parses yields none and
   * the primary query answers alone.
   */
  @SuppressWarnings("unchecked")
  private static List<QueryPlan.PlanSection> parseSections(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<QueryPlan.PlanSection> sections = new java.util.ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        continue;
      }
      Object title = map.get("title");
      Object sectionQuery = map.get("queryText");
      if (title instanceof String t && sectionQuery instanceof String q
          && !t.isBlank() && !q.isBlank()) {
        sections.add(new QueryPlan.PlanSection(t, q));
      }
    }
    // Hard cap: extra views beyond three stop being an "answer" and become a dashboard.
    return sections.size() > MAX_SECTIONS ? sections.subList(0, MAX_SECTIONS) : sections;
  }

  @Override
  public Long countRows(UUID dataSourceId, String ownerUserId, QueryPlan plan) {
    try {
      String probeSql = "SELECT COUNT(*) AS total_rows FROM ( " + plan.queryText()
          + " ) whyql_count_probe";
      QueryPlan probe = new QueryPlan(
          plan.engine(), probeSql, plan.explanation(), plan.assumptions(),
          plan.clarifyingQuestions(), List.of());
      TabularResult result = queryExecutor.execute(
          dataSourceId, ownerUserId, probe,
          new ExecutionLimits(COUNT_TIMEOUT_SECONDS, 2), VisibilityRules.none());
      if (result.rows() == null || result.rows().isEmpty()) {
        return null;
      }
      Object cell = result.rows().get(0).get(0);
      if (cell instanceof Number number) {
        return number.longValue();
      }
      return Long.parseLong(String.valueOf(cell));
    } catch (Exception e) {
      // The probe is never load-bearing: an unreadable count means callers
      // report ">N rows" instead of the exact figure.
      log.debug("Count probe failed for data source {}: {}", dataSourceId, e.getMessage());
      return null;
    }
  }

  @Override
  public QueryPlan validateQuery(QueryPlan plan, SchemaSnapshot snapshot) {
    SqlValidator.ValidationResult validation = sqlValidator.validate(
        plan.queryText(), snapshot, VisibilityRules.none());
    if (!validation.valid()) {
      throw new IllegalStateException(
          "Validation failed: " + String.join("; ", validation.errors()));
    }
    return plan;
  }

  @Override
  public TabularResult executeQuery(UUID dataSourceId, String ownerUserId,
      QueryPlan plan) {
    try {
      return queryExecutor.execute(
          dataSourceId, ownerUserId, plan,
          QueryExecutor.defaultLimits(), VisibilityRules.none());
    } catch (com.whyql.whyql.datasource.service.DataSourceException e) {
      throw new RuntimeException("Execution failed: " + e.getMessage(), e);
    }
  }

  /**
   * Fast path for schema-meta questions: matched by {@link SchemaMetaQuestions}
   * and answered from a live introspection of the data source. Any mismatch or
   * failure falls through to empty, letting the caller use the normal pipeline.
   */
  @Override
  public Optional<TabularResult> answerSchemaQuestion(UUID dataSourceId, String ownerUserId,
      String question) {
    Optional<SchemaMetaQuestions.Kind> kind = SchemaMetaQuestions.classify(question);
    if (kind.isEmpty()) {
      return Optional.empty();
    }
    List<SchemaSnapshot.SchemaTable> tables;
    List<String> reportsSchemas;
    try {
      SchemaSnapshot snapshot = gateway.introspect(dataSourceId, ownerUserId);
      tables = snapshot.tables();
      reportsSchemas = snapshot.schemas();
    } catch (Exception e) {
      // Let the normal pipeline surface the (likely) connection problem.
      log.debug("Schema-meta fast path skipped: introspection failed: {}", e.getMessage());
      return Optional.empty();
    }
    List<SchemaSnapshot.SchemaTable> sorted = tables == null ? List.of()
        : tables.stream()
            .filter(t -> t.name() != null)
            .sorted(Comparator.comparing(SchemaSnapshot.SchemaTable::name,
                String.CASE_INSENSITIVE_ORDER))
            .toList();
    List<String> schemas = schemaNames(reportsSchemas, sorted);
    boolean schemaKnown = kind.get() == SchemaMetaQuestions.Kind.SCHEMA_COUNT
        || kind.get() == SchemaMetaQuestions.Kind.SCHEMA_LIST;
    if (schemaKnown && schemas.isEmpty()) {
      // This data source carries no namespace information (SQL upload): the
      // normal pipeline answers or clarifies the usual way.
      return Optional.empty();
    }

    return Optional.of(switch (kind.get()) {
      case TABLE_COUNT -> new TabularResult(
          List.of(new TabularResult.ResultColumn("table_count", "BIGINT")),
          List.of(List.of(sorted.size())),
          false);
      case TABLE_LIST -> new TabularResult(
          List.of(new TabularResult.ResultColumn("table_name", "VARCHAR"),
              new TabularResult.ResultColumn("column_count", "BIGINT")),
          sorted.stream().map(t -> List.<Object>of(
              t.name(), (Object) (t.columns() == null ? 0 : t.columns().size()))).toList(),
          false);
      case SCHEMA_COUNT -> new TabularResult(
          List.of(new TabularResult.ResultColumn("schema_count", "BIGINT")),
          List.of(List.of(schemas.size())),
          false);
      case SCHEMA_LIST -> new TabularResult(
          List.of(new TabularResult.ResultColumn("schema_name", "VARCHAR")),
          schemas.stream().map(s -> List.of((Object) s)).toList(),
          false);
      case FOREIGN_KEYS -> fkBuckets(sorted);
    });
  }

  /**
   * Foreign-key inventory from the snapshot: one row for tables that have
   * foreign keys and one for those that don't, each with its count and the
   * table names — so a "how many" question and a "which tables" question are
   * both answered by the same result. Tables with unreadable FK metadata count
   * as "none" (null fks list == nothing asserted).
   */
  private static TabularResult fkBuckets(List<SchemaSnapshot.SchemaTable> sorted) {
    List<SchemaSnapshot.SchemaTable> withFk = new java.util.ArrayList<>();
    List<SchemaSnapshot.SchemaTable> withoutFk = new java.util.ArrayList<>();
    for (SchemaSnapshot.SchemaTable t : sorted) {
      if (t.foreignKeys() != null && !t.foreignKeys().isEmpty()) {
        withFk.add(t);
      } else {
        withoutFk.add(t);
      }
    }
    return new TabularResult(
        List.of(new TabularResult.ResultColumn("fkey_status", "VARCHAR"),
            new TabularResult.ResultColumn("table_count", "BIGINT"),
            new TabularResult.ResultColumn("table_names", "VARCHAR")),
        List.of(
            List.of("HAS_FOREIGN_KEYS", (Object) withFk.size(), names(withFk)),
            List.of("NO_FOREIGN_KEYS", (Object) withoutFk.size(), names(withoutFk))),
        false);
  }

  private static String names(List<SchemaSnapshot.SchemaTable> tables) {
    return tables.stream().map(SchemaSnapshot.SchemaTable::name)
        .reduce((a, b) -> a + ", " + b).orElse("");
  }

  /**
   * Schema names for meta answers: JDBC introspection reports the readable
   * namespaces directly; without that, qualified duplicate table names
   * ("schema.table") still name them. An empty result means the data source
   * carries no namespace information at all — the caller cannot answer, so it
   * returns empty and the normal pipeline takes over.
   */
  private static List<String> schemaNames(List<String> reported,
      List<SchemaSnapshot.SchemaTable> tables) {
    LinkedHashSet<String> names = new LinkedHashSet<>();
    if (reported != null) {
      reported.stream().filter(s -> s != null && !s.isBlank()).forEach(names::add);
    }
    if (names.isEmpty() && tables != null) {
      tables.stream()
          .map(SchemaSnapshot.SchemaTable::name)
          .filter(n -> n != null && n.contains("."))
          .map(n -> n.substring(0, n.indexOf('.'))
              .toLowerCase(Locale.ROOT))
          .forEach(names::add);
    }
    return List.copyOf(names);
  }

  @Override
  public boolean wantsExplanation(String question) {
    return WhyQuestions.classify(question);
  }

  @Override
  public String routeQuestion(String question) {
    return questionRouter.route(question);
  }

  @Override
  public String answerGeneral(String question) {
    try {
      return chatClient.prompt()
          .system("""
              You are WhyQL's assistant. The user asked a question that is not \
              answerable from their database. Answer it directly and concisely \
              (at most 120 words, plain prose). You have no access to data — never \
              pretend to query anything. If the question looks like it really wants \
              the user's own data (counts, rows in their tables), say so and give a \
              one-sentence example of how to ask it as a data question.""")
          .user(question)
          .call()
          .content();
    } catch (Exception e) {
      log.warn("General answer failed: {}", e.getMessage());
      return null;
    }
  }

  @Override
  public String analyzeExplanation(String question, TabularResult result) {
    try {
      return analysisNarrator.narrate(question, result);
    } catch (Exception e) {
      log.warn("Narrative analysis failed, keeping the plain result: {}", e.getMessage());
      return null;
    }
  }

  private QueryPlan validateQueryWithRetry(QueryPlan plan, SchemaSnapshot snapshot) {
    QueryPlan current = plan;
    for (int attempt = 1; attempt <= MAX_SELF_CORRECTION_ATTEMPTS; attempt++) {
      try {
        return validateQuery(current, snapshot);
      } catch (IllegalStateException e) {
        log.warn("Validation failed (attempt {}): {}", attempt, e.getMessage());
        if (attempt < MAX_SELF_CORRECTION_ATTEMPTS) {
          // Self-correct: feed errors back into prompt
          String question = current.queryText();
          // Re-generate with error feedback
          String corrected = current.queryText() + "\n-- Previous errors: "
              + e.getMessage() + "\nPlease fix.";
          current = new QueryPlan(
              current.engine(),
              corrected,
              current.explanation() + " [attempt " + attempt + "]",
              current.assumptions(),
              current.clarifyingQuestions());
          continue;
        }
        throw e;
      }
    }
    throw new IllegalStateException("Validation failed after "
        + MAX_SELF_CORRECTION_ATTEMPTS + " attempts");
  }

  private QueryResult executePlan(UUID dataSourceId, String ownerUserId,
      QueryPlan plan) {
    try {
      TabularResult result = queryExecutor.execute(
          dataSourceId, ownerUserId, plan,
          QueryExecutor.defaultLimits(), VisibilityRules.none());
      return new QueryResult("COMPLETED", plan, result, null, List.of());
    } catch (com.whyql.whyql.datasource.service.DataSourceException e) {
      return new QueryResult("FAILED", plan, null,
          "execution failed: " + e.getMessage(), List.of());
    }
  }

  private String requestOwnerUserId(QueryRequest request) {
    // Phase 4: no auth context yet; use a placeholder. Phase 6 will pass the
    // authenticated user ID from the GraphQL resolver.
    return "system";
  }
}
