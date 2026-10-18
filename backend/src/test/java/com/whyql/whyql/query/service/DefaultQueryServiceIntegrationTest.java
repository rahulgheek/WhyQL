package com.whyql.whyql.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

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
import tools.jackson.databind.ObjectMapper;

/**
 * Unit test for the query pipeline with mocked LLM and datasource gateway.
 * Verifies the full flow: prompt -> LLM -> validation -> execution -> result.
 */
class DefaultQueryServicePipelineTest {

  @Test
  void askQuestionReturnsCompletedResult() throws Exception {
    UUID dataSourceId = UUID.randomUUID();

    SchemaSnapshot snapshot = new SchemaSnapshot(
        "test",
        List.of(new SchemaSnapshot.SchemaTable(
            "customers",
            List.of(
                new SchemaSnapshot.SchemaColumn("id", "BIGINT", false, false),
                new SchemaSnapshot.SchemaColumn("name", "TEXT", true, false)
            ),
            List.of("id"),
            List.of()
        )),
        java.time.Instant.now());

    DatasourceGateway gateway = mock(DatasourceGateway.class);
    SchemaDocsService schemaDocs = mock(SchemaDocsService.class);
    when(schemaDocs.search(any(UUID.class), anyString(), anyInt())).thenReturn(List.of());
    when(schemaDocs.searchExemplars(any(UUID.class), anyString(), anyInt()))
        .thenReturn(List.of());
    when(gateway.get(any(UUID.class), any(String.class)))
        .thenReturn(new com.whyql.whyql.datasource.DataSourceView(
            dataSourceId, "test-ds", com.whyql.whyql.datasource.Engine.POSTGRES,
            false, java.time.Instant.now()));
    when(gateway.introspect(any(UUID.class), any(String.class))).thenReturn(snapshot);
    when(gateway.execute(any(UUID.class), any(String.class), any(),
        any(ExecutionLimits.class), any(VisibilityRules.class)))
        .thenReturn(new TabularResult(
            List.of(new TabularResult.ResultColumn("id", "BIGINT"),
                new TabularResult.ResultColumn("name", "TEXT")),
            List.of(List.of(1L, "Alice"), List.of(2L, "Bob")),
            false));

    // Mock ChatModel -> ChatClient
    String json = """
        {"queryText":"SELECT id, name FROM customers LIMIT 10",
         "explanation":"Count customers",
         "assumptions":[],
         "clarifyingQuestions":[]}
        """;
    AssistantMessage message = new AssistantMessage(json);
    Generation generation = new Generation(message);
    ChatResponse response = new ChatResponse(List.of(generation));
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(response);
    ChatClient chatClient = ChatClient.builder(chatModel).build();

    DefaultQueryService service = new DefaultQueryService(
        chatClient, new tools.jackson.databind.ObjectMapper(),
        gateway, new PromptBuilder(), new SqlValidator(),
        new QueryExecutor(gateway), new QueryCache(), new QueryTools(),
        schemaDocs,
        CircuitBreaker.of("llm", CircuitBreakerConfig.ofDefaults()));

    QueryService.QueryRequest request = new QueryService.QueryRequest(
        dataSourceId,
        "How many customers are there?",
        List.of("descriptive"),
        "auto",
        "echarts");

    QueryService.QueryResult result = service.askQuestion(request);

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.plan()).isNotNull();
    assertThat(result.plan().queryText()).isEqualTo("SELECT id, name FROM customers LIMIT 10");
    assertThat(result.result()).isNotNull();
    assertThat(result.result().rows()).hasSize(2);
    assertThat(result.clarifyingQuestions()).isEmpty();
  }

  @Test
  void askQuestionReturnsNeedsClarification() throws Exception {
    UUID dataSourceId = UUID.randomUUID();

    SchemaSnapshot snapshot = new SchemaSnapshot(
        "test",
        List.of(new SchemaSnapshot.SchemaTable(
            "customers",
            List.of(
                new SchemaSnapshot.SchemaColumn("id", "BIGINT", false, false),
                new SchemaSnapshot.SchemaColumn("name", "TEXT", true, false)
            ),
            List.of("id"),
            List.of()
        )),
        java.time.Instant.now());

    DatasourceGateway gateway = mock(DatasourceGateway.class);
    SchemaDocsService schemaDocs = mock(SchemaDocsService.class);
    when(schemaDocs.search(any(UUID.class), anyString(), anyInt())).thenReturn(List.of());
    when(schemaDocs.searchExemplars(any(UUID.class), anyString(), anyInt()))
        .thenReturn(List.of());
    when(gateway.get(any(UUID.class), any(String.class)))
        .thenReturn(new com.whyql.whyql.datasource.DataSourceView(
            dataSourceId, "test-ds", com.whyql.whyql.datasource.Engine.POSTGRES,
            false, java.time.Instant.now()));
    when(gateway.introspect(any(UUID.class), any(String.class))).thenReturn(snapshot);

    String json = """
        {"queryText":"",
         "explanation":"Need more info",
         "assumptions":[],
         "clarifyingQuestions":["Which time period?"]}
        """;
    AssistantMessage message = new AssistantMessage(json);
    Generation generation = new Generation(message);
    ChatResponse response = new ChatResponse(List.of(generation));
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(response);
    ChatClient chatClient = ChatClient.builder(chatModel).build();

    DefaultQueryService service = new DefaultQueryService(
        chatClient, new tools.jackson.databind.ObjectMapper(),
        gateway, new PromptBuilder(), new SqlValidator(),
        new QueryExecutor(gateway), new QueryCache(), new QueryTools(),
        schemaDocs,
        CircuitBreaker.of("llm", CircuitBreakerConfig.ofDefaults()));

    QueryService.QueryRequest request = new QueryService.QueryRequest(
        dataSourceId,
        "How many customers are there?",
        List.of("descriptive"),
        "auto",
        "echarts");

    QueryService.QueryResult result = service.askQuestion(request);

    assertThat(result.status()).isEqualTo("NEEDS_CLARIFICATION");
    assertThat(result.clarifyingQuestions()).containsExactly("Which time period?");
  }
}
