package com.whyql.whyql.query.service;

import java.util.List;
import java.util.Map;
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
import com.whyql.whyql.datasource.DataSourceAdapter.AdapterException;
import com.whyql.whyql.datasource.DatasourceGateway;
import com.whyql.whyql.datasource.ExecutionLimits;
import com.whyql.whyql.datasource.QueryPlan;
import com.whyql.whyql.datasource.SchemaSnapshot;
import com.whyql.whyql.datasource.TabularResult;
import com.whyql.whyql.datasource.VisibilityRules;
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
      CircuitBreaker circuitBreaker) {
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
      plan = generateQuery(dataSourceId, snapshot, question);
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
  public QueryPlan generateQuery(UUID dataSourceId, SchemaSnapshot snapshot, String question) {
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
        snapshot, List.of(), "PostgreSQL", question, relevantContext, exemplarLines);

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

    return new QueryPlan(
        com.whyql.whyql.datasource.Engine.POSTGRES,
        queryText,
        explanation,
        assumptions,
        clarifyingQuestions);
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
    } catch (com.whyql.whyql.datasource.DataSourceException e) {
      throw new RuntimeException("Execution failed: " + e.getMessage(), e);
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
    } catch (com.whyql.whyql.datasource.DataSourceException e) {
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
