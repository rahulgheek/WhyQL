package com.whyql.whyql.api.resolver;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import com.whyql.whyql.api.dashboard.DashboardCardMapper;
import com.whyql.whyql.api.model.AddDataSourceInput;
import com.whyql.whyql.api.model.AddDataSourcePayload;
import com.whyql.whyql.api.model.AskQuestionInput;
import com.whyql.whyql.api.model.AskQuestionPayload;
import com.whyql.whyql.api.model.DashboardCard;
import com.whyql.whyql.dashboard.service.DashboardService;
import com.whyql.whyql.datasource.service.DataSourceService;
import com.whyql.whyql.pipeline.service.AsyncQueryOrchestrator;

/**
 * Mutation resolvers. Note that {@code askQuestion} never blocks on the LLM:
 * it queues a job (Kafka pipeline) and returns the jobId immediately with
 * QUEUED status.
 */
@Controller
public class ApiMutationResolver {

  private final DataSourceService dataSourceService;
  private final AsyncQueryOrchestrator orchestrator;
  private final com.whyql.whyql.jobs.service.JobService jobService;
  private final DashboardService dashboardService;
  private final DashboardCardMapper dashboardCardMapper;
  private final com.whyql.whyql.chat.service.ChatService chatService;
  private final com.whyql.whyql.api.chat.ChatMapper chatMapper;

  public ApiMutationResolver(DataSourceService dataSourceService,
      AsyncQueryOrchestrator orchestrator,
      com.whyql.whyql.jobs.service.JobService jobService,
      DashboardService dashboardService,
      DashboardCardMapper dashboardCardMapper,
      com.whyql.whyql.chat.service.ChatService chatService,
      com.whyql.whyql.api.chat.ChatMapper chatMapper) {
    this.dataSourceService = dataSourceService;
    this.orchestrator = orchestrator;
    this.jobService = jobService;
    this.dashboardService = dashboardService;
    this.dashboardCardMapper = dashboardCardMapper;
    this.chatService = chatService;
    this.chatMapper = chatMapper;
  }

  @MutationMapping
  public AddDataSourcePayload addDataSource(@Argument AddDataSourceInput input,
      Authentication auth) {
    AuthGuards.requireUser(auth);
    var result = dataSourceService.addByUrl(
        auth.getName(),
        input.name(),
        input.engine(),
        input.jdbcUrl(),
        input.username(),
        input.password());
    return AddDataSourcePayload.from(result);
  }

  @MutationMapping
  public AskQuestionPayload askQuestion(@Argument AskQuestionInput input,
      Authentication auth) {
    AuthGuards.requireUser(auth);
    com.whyql.whyql.jobs.domain.Job job = orchestrator.startQuestion(
        input.dataSourceId(), auth.getName(), input.question(), input.chatSessionId());
    return new AskQuestionPayload(job.getId(), job.getStatus().name());
  }

  /**
   * Creates an empty chat session for the ask page. dataSourceId optional:
   * sessions may hold general questions with no data source attached.
   */
  @MutationMapping
  public com.whyql.whyql.api.model.ChatSession createChatSession(
      @Argument String title, @Argument java.util.UUID dataSourceId, Authentication auth) {
    AuthGuards.requireUser(auth);
    if (dataSourceId != null) {
      // The source must belong to the caller; attaching someone else's
      // source id to a session would leak its name later.
      dataSourceService.get(dataSourceId, auth.getName());
    }
    return chatMapper.toGql(chatService.create(auth.getName(), title, dataSourceId));
  }

  @MutationMapping
  public com.whyql.whyql.api.model.ChatSession renameChatSession(
      @Argument java.util.UUID id, @Argument String title, Authentication auth) {
    AuthGuards.requireUser(auth);
    AuthGuards.requireOwnSession(chatService, id, auth);
    return chatMapper.toGql(chatService.rename(auth.getName(), id, title));
  }

  @MutationMapping
  public Boolean deleteChatSession(@Argument java.util.UUID id, Authentication auth) {
    AuthGuards.requireUser(auth);
    AuthGuards.requireOwnSession(chatService, id, auth);
    chatService.delete(auth.getName(), id);
    return true;
  }

  /**
   * Saves a completed job to the caller's dashboard as a pinned card. Saving
   * the same job twice returns the existing card (idempotent pin). Requires
   * the job to be the caller's, COMPLETED, and backed by an owned data
   * source; violations map onto the typed GraphQL errors.
   */
  @MutationMapping
  public DashboardCard saveToDashboard(@Argument java.util.UUID jobId, Authentication auth) {
    com.whyql.whyql.jobs.domain.Job job = AuthGuards.requireOwnJob(jobService, jobId, auth);
    try {
      var view = dataSourceService.get(job.getDataSourceId(), auth.getName());
      com.whyql.whyql.dashboard.domain.DashboardCard card =
          dashboardService.saveFromJob(job, view.engine().name());
      return dashboardCardMapper.toGql(card);
    } catch (IllegalArgumentException notFound) {
      // Covers a data source deleted between ask and save.
      throw new NotFoundGraphQLException(notFound.getMessage());
    } catch (IllegalStateException notCompleted) {
      throw new ForbiddenGraphQLException(notCompleted.getMessage());
    }
  }

  @MutationMapping
  public boolean removeDashboardCard(@Argument java.util.UUID id, Authentication auth) {
    AuthGuards.requireUser(auth);
    if (!dashboardService.remove(id, auth.getName())) {
      throw new NotFoundGraphQLException("Dashboard card not found: " + id);
    }
    return true;
  }
}
