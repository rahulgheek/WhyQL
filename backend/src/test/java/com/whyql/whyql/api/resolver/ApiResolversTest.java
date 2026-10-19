package com.whyql.whyql.api.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import com.whyql.whyql.api.dashboard.DashboardCardMapper;
import com.whyql.whyql.api.job.JobMapper;
import com.whyql.whyql.api.job.JobUpdateHub;
import com.whyql.whyql.api.model.AddDataSourceInput;
import com.whyql.whyql.api.model.AskQuestionInput;
import com.whyql.whyql.api.model.AskQuestionPayload;
import com.whyql.whyql.api.model.DataSource;
import com.whyql.whyql.api.model.DashboardCard;
import com.whyql.whyql.api.model.Job;
import com.whyql.whyql.dashboard.service.DashboardService;
import com.whyql.whyql.datasource.domain.DataSourceView;
import com.whyql.whyql.datasource.domain.Engine;
import com.whyql.whyql.datasource.service.DataSourceService;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.pipeline.service.AsyncQueryOrchestrator;

/**
 * Unit tests for resolvers: payload mapping and per-field authorization
 * (anonymous callers rejected; jobs visible to their owner only).
 */
class ApiResolversTest {

  private DataSourceService dataSourceService;
  private JobService jobService;
  private AsyncQueryOrchestrator orchestrator;
  private DashboardService dashboardService;

  private ApiQueryResolver queryResolver;
  private ApiMutationResolver mutationResolver;
  private ApiSubscriptionResolver subscriptionResolver;

  private com.whyql.whyql.jobs.domain.Job ownedJob;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dataSourceService = mock(DataSourceService.class);
    jobService = mock(JobService.class);
    orchestrator = mock(AsyncQueryOrchestrator.class);
    dashboardService = mock(DashboardService.class);
    queryResolver = new ApiQueryResolver(dataSourceService, jobService,
        new JobMapper(new tools.jackson.databind.ObjectMapper()), dashboardService,
        new DashboardCardMapper(new tools.jackson.databind.ObjectMapper()));
    mutationResolver = new ApiMutationResolver(dataSourceService, orchestrator,
        jobService, dashboardService,
        new DashboardCardMapper(new tools.jackson.databind.ObjectMapper()));
    subscriptionResolver = new ApiSubscriptionResolver(jobService, new JobUpdateHub(new JobMapper(new tools.jackson.databind.ObjectMapper())));

    ownedJob = new com.whyql.whyql.jobs.domain.Job();
    ownedJob.setId(UUID.randomUUID());
    ownedJob.setOwnerUserId("user-1");
    ownedJob.setDataSourceId(UUID.randomUUID());
    ownedJob.setQuestion("q");
    ownedJob.setStatus(JobStatus.COMPLETED);
    ownedJob.setResultJson(
        "{\"columns\":[{\"name\":\"user_count\",\"typeName\":\"int8\"}],\"rows\":[[14]],\"truncated\":false}");

    when(jobService.findById(ownedJob.getId())).thenReturn(ownedJob);
  }

  private Authentication user(String id) {
    Authentication auth = mock(Authentication.class);
    when(auth.getName()).thenReturn(id);
    when(auth.isAuthenticated()).thenReturn(true);
    return auth;
  }

  @Test
  void dataSourcesMapsViews() {
    when(dataSourceService.list("user-1")).thenReturn(List.of(
        new DataSourceView(UUID.randomUUID(), "demo", Engine.POSTGRES, false, Instant.now())));

    List<DataSource> views = queryResolver.dataSources(user("user-1"));

    assertThat(views).hasSize(1);
    assertThat(views.get(0).name()).isEqualTo("demo");
    assertThat(views.get(0).engine()).isEqualTo(Engine.POSTGRES);
  }

  @Test
  void dataSourcesRejectsAnonymousCallers() {
    assertThrows(UnauthorizedGraphQLException.class, () -> queryResolver.dataSources(null));
  }

  @Test
  void jobReturnsOwnerView() {
    Job view = queryResolver.job(ownedJob.getId(), user("user-1"));

    assertThat(view.id()).isEqualTo(ownedJob.getId());
    assertThat(view.status()).isEqualTo("COMPLETED");
  }

  @Test
  void jobRejectsOtherUsers() {
    assertThrows(ForbiddenGraphQLException.class,
        () -> queryResolver.job(ownedJob.getId(), user("intruder")));
  }

  @Test
  void jobRejectsUnknownIds() {
    assertThrows(NotFoundGraphQLException.class,
        () -> queryResolver.job(UUID.randomUUID(), user("user-1")));
  }

  @Test
  void addDataSourceMapsPayloadWithWarnings() {
    UUID id = UUID.randomUUID();
    var view = new DataSourceView(id, "new ds", Engine.MYSQL, true, Instant.now());
    when(dataSourceService.addByUrl(eq("user-1"), eq("new ds"), eq(Engine.MYSQL),
        anyString(), any(), any()))
        .thenReturn(new DataSourceService.AddResult(view, List.of("sandbox mode")));

    var payload = mutationResolver.addDataSource(
        new AddDataSourceInput("new ds", Engine.MYSQL, "jdbc:mysql://x", null, null),
        user("user-1"));

    assertThat(payload.dataSource().id()).isEqualTo(id);
    assertThat(payload.warnings()).containsExactly("sandbox mode");
  }

  @Test
  void addDataSourceRejectsAnonymousCallers() {
    assertThrows(UnauthorizedGraphQLException.class, () -> mutationResolver.addDataSource(
        new AddDataSourceInput("n", Engine.MYSQL, "jdbc:mysql://x", null, null), null));
  }

  @Test
  void askQuestionQueuesJobAndReturnsIdsImmediately() {
    com.whyql.whyql.jobs.domain.Job queued = new com.whyql.whyql.jobs.domain.Job();
    queued.setId(ownedJob.getId());
    queued.setOwnerUserId("user-1");
    queued.setDataSourceId(ownedJob.getDataSourceId());
    queued.setQuestion("revenue by country");
    queued.setStatus(JobStatus.QUEUED);
    when(orchestrator.startQuestion(any(UUID.class), anyString(), anyString()))
        .thenReturn(queued);

    AskQuestionPayload payload = mutationResolver.askQuestion(
        new AskQuestionInput(ownedJob.getDataSourceId(), "revenue by country"),
        user("user-1"));

    assertThat(payload.jobId()).isEqualTo(queued.getId());
    assertThat(payload.status()).isEqualTo(JobStatus.QUEUED.name());
  }

  @Test
  void askQuestionRejectsAnonymousCallers() {
    assertThrows(UnauthorizedGraphQLException.class, () -> mutationResolver.askQuestion(
        new AskQuestionInput(UUID.randomUUID(), "q"), null));
  }

  @Test
  void jobUpdatesOnlyStreamsToTheOwner() {
    assertThrows(ForbiddenGraphQLException.class,
        () -> subscriptionResolver.jobUpdates(ownedJob.getId(), user("intruder")));
  }

  @Test
  void jobUpdatesStreamsTerminalSnapshotForOwner() {
    var flux = subscriptionResolver.jobUpdates(ownedJob.getId(), user("user-1"));

    List<Job> received = flux.collectList().block(java.time.Duration.ofSeconds(2));

    assertThat(received).hasSize(1);
    assertThat(received.get(0).status()).isEqualTo("COMPLETED");
  }

  @Test
  void saveToDashboardSnapshotsOwnCompletedJob() {
    var entity = new com.whyql.whyql.dashboard.domain.DashboardCard(
        UUID.randomUUID(), "user-1", ownedJob.getDataSourceId(), ownedJob.getId(),
        ownedJob.getQuestion(), "POSTGRES", ownedJob.getResultJson(), Instant.now());
    when(dataSourceService.get(ownedJob.getDataSourceId(), "user-1"))
        .thenReturn(new DataSourceView(ownedJob.getDataSourceId(), "ds", Engine.POSTGRES,
            false, Instant.now()));
    when(dashboardService.saveFromJob(ownedJob, "POSTGRES")).thenReturn(entity);

    DashboardCard card = mutationResolver.saveToDashboard(ownedJob.getId(), user("user-1"));

    assertThat(card.jobId()).isEqualTo(ownedJob.getId());
    assertThat(card.engine()).isEqualTo("POSTGRES");
    assertThat(card.result()).isNotNull();
  }

  @Test
  void saveToDashboardRejectsAnonymousCallers() {
    assertThrows(UnauthorizedGraphQLException.class,
        () -> mutationResolver.saveToDashboard(ownedJob.getId(), null));
  }

  @Test
  void saveToDashboardRejectsForeignJobs() {
    assertThrows(ForbiddenGraphQLException.class,
        () -> mutationResolver.saveToDashboard(ownedJob.getId(), user("intruder")));
  }

  @Test
  void saveToDashboardRejectsIncompleteJobs() {
    ownedJob.setStatus(JobStatus.GENERATING);
    when(dataSourceService.get(ownedJob.getDataSourceId(), "user-1"))
        .thenReturn(new DataSourceView(ownedJob.getDataSourceId(), "ds", Engine.POSTGRES,
            false, Instant.now()));
    when(dashboardService.saveFromJob(ownedJob, "POSTGRES"))
        .thenThrow(new IllegalStateException("Only COMPLETED jobs can be saved"));

    assertThrows(ForbiddenGraphQLException.class,
        () -> mutationResolver.saveToDashboard(ownedJob.getId(), user("user-1")));
  }

  @Test
  void saveToDashboardMapsDeletedDatasourceToNotFound() {
    ownedJob.setStatus(JobStatus.QUEUED);
    when(dataSourceService.get(ownedJob.getDataSourceId(), "user-1"))
        .thenThrow(new IllegalArgumentException("Data source not found"));

    assertThrows(NotFoundGraphQLException.class,
        () -> mutationResolver.saveToDashboard(ownedJob.getId(), user("user-1")));
  }

  @Test
  void dashboardListsOnlyOwnersCards() {
    var entity = new com.whyql.whyql.dashboard.domain.DashboardCard(
        UUID.randomUUID(), "user-1", ownedJob.getDataSourceId(), ownedJob.getId(),
        "q", "POSTGRES", ownedJob.getResultJson(), Instant.now());
    when(dashboardService.list("user-1")).thenReturn(List.of(entity));

    List<DashboardCard> cards = queryResolver.dashboard(user("user-1"));

    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).question()).isEqualTo("q");
    assertThat(cards.get(0).result().rows().get(0).get(0)).isEqualTo(14);
  }

  @Test
  void dashboardRejectsAnonymousCallers() {
    assertThrows(UnauthorizedGraphQLException.class, () -> queryResolver.dashboard(null));
  }

  @Test
  void removeDashboardCardMapsUnknownIds() {
    when(dashboardService.remove(UUID.randomUUID(), "user-1")).thenReturn(false);

    assertThrows(NotFoundGraphQLException.class,
        () -> mutationResolver.removeDashboardCard(UUID.randomUUID(), user("user-1")));
  }

  @Test
  void removeDashboardCardDeletesOwnedCard() {
    UUID cardId = UUID.randomUUID();
    when(dashboardService.remove(cardId, "user-1")).thenReturn(true);

    assertThat(mutationResolver.removeDashboardCard(cardId, user("user-1"))).isTrue();
  }
}
