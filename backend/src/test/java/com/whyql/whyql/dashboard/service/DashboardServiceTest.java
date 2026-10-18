package com.whyql.whyql.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.whyql.whyql.dashboard.domain.DashboardCard;
import com.whyql.whyql.dashboard.repository.DashboardCardRepository;
import com.whyql.whyql.jobs.domain.Job;
import com.whyql.whyql.jobs.domain.JobStatus;

/**
 * Pure Mockito tests for pinning completed jobs to dashboards: snapshot
 * semantics, idempotent re-save, completion precondition, owner-scoped
 * listing and removal.
 */
class DashboardServiceTest {

  private DashboardCardRepository repository;
  private DashboardService service;

  @BeforeEach
  void setUp() {
    repository = mock(DashboardCardRepository.class);
    service = new DashboardService(repository);
  }

  private Job completedJob() {
    Job job = new Job();
    job.setId(UUID.randomUUID());
    job.setOwnerUserId("user-1");
    job.setDataSourceId(UUID.randomUUID());
    job.setQuestion("How many users?");
    job.setStatus(JobStatus.COMPLETED);
    job.setResultJson("{\"columns\":[{\"name\":\"user_count\",\"typeName\":\"int8\"}],\"rows\":[[14]],\"truncated\":false}");
    return job;
  }

  @Test
  void saveFromJobSnapshotsResult() {
    Job job = completedJob();
    when(repository.findByOwnerUserIdAndJobId("user-1", job.getId()))
        .thenReturn(Optional.empty());
    when(repository.save(any(DashboardCard.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    DashboardCard card = service.saveFromJob(job, "POSTGRES");

    assertThat(card.getOwnerUserId()).isEqualTo("user-1");
    assertThat(card.getJobId()).isEqualTo(job.getId());
    assertThat(card.getDataSourceId()).isEqualTo(job.getDataSourceId());
    assertThat(card.getQuestion()).isEqualTo("How many users?");
    assertThat(card.getEngine()).isEqualTo("POSTGRES");
    assertThat(card.getResultJson()).contains("user_count");
    assertThat(card.getCreatedAt()).isNotNull();
    verify(repository).save(any(DashboardCard.class));
  }

  @Test
  void reSavingSameJobReturnsExistingCard() {
    Job job = completedJob();
    DashboardCard existing = new DashboardCard(
        UUID.randomUUID(), "user-1", job.getDataSourceId(), job.getId(),
        "How many users?", "POSTGRES", "old-json", Instant.now().minusSeconds(60));
    when(repository.findByOwnerUserIdAndJobId("user-1", job.getId()))
        .thenReturn(Optional.of(existing));

    DashboardCard card = service.saveFromJob(job, "POSTGRES");

    assertThat(card).isSameAs(existing);
    verify(repository, never()).save(any(DashboardCard.class));
  }

  @Test
  void nonCompletedJobIsRejected() {
    for (JobStatus status : JobStatus.values()) {
      if (status == JobStatus.COMPLETED) {
        continue;
      }
      Job job = completedJob();
      job.setStatus(status);

      assertThatThrownBy(() -> service.saveFromJob(job, "POSTGRES"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(status.name());
    }
    verify(repository, never()).save(any(DashboardCard.class));
  }

  @Test
  void listReturnsOnlyOwnersCards() {
    List<DashboardCard> cards = List.of(
        new DashboardCard(UUID.randomUUID(), "user-1", UUID.randomUUID(),
            UUID.randomUUID(), "q1", "POSTGRES", null, Instant.now()));
    when(repository.findByOwnerUserIdOrderByCreatedAtDesc("user-1")).thenReturn(cards);

    assertThat(service.list("user-1")).isSameAs(cards);
    verify(repository).findByOwnerUserIdOrderByCreatedAtDesc("user-1");
  }

  @Test
  void removeDeletesOwnedCard() {
    DashboardCard card = new DashboardCard();
    card.setId(UUID.randomUUID());
    when(repository.findByIdAndOwnerUserId(card.getId(), "user-1"))
        .thenReturn(Optional.of(card));

    assertThat(service.remove(card.getId(), "user-1")).isTrue();
    verify(repository).delete(card);
  }

  @Test
  void removeReturnsFalseForForeignOrMissingCard() {
    UUID cardId = UUID.randomUUID();
    when(repository.findByIdAndOwnerUserId(cardId, "user-1")).thenReturn(Optional.empty());

    assertThat(service.remove(cardId, "user-1")).isFalse();
    verify(repository, never()).delete(any(DashboardCard.class));
  }
}
