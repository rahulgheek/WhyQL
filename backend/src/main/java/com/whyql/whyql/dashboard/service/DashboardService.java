package com.whyql.whyql.dashboard.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.whyql.whyql.dashboard.domain.DashboardCard;
import com.whyql.whyql.dashboard.repository.DashboardCardRepository;
import com.whyql.whyql.jobs.domain.Job;

/**
 * Pinned results on a user's dashboard. The caller (api resolver layer)
 * resolves and ownership-checks the job and data source first; this service
 * snapshots the COMPLETED job's result into a card. Saving the same job
 * again is idempotent: the first card wins.
 */
@Service
public class DashboardService {

  private final DashboardCardRepository repository;

  public DashboardService(DashboardCardRepository repository) {
    this.repository = repository;
  }

  /**
   * Pins a completed job to the caller's dashboard. {@code engine} is taken
   * from the caller's (ownership-checked) data source view. Throws
   * {@link IllegalStateException} when the job is not COMPLETED — the api
   * layer maps that onto its typed GraphQL errors.
   */
  @Transactional
  public DashboardCard saveFromJob(Job job, String engine) {
    if (job.getStatus() != com.whyql.whyql.jobs.domain.JobStatus.COMPLETED) {
      throw new IllegalStateException(
          "Only COMPLETED jobs can be saved to the dashboard (job " + job.getId()
              + " is " + job.getStatus() + ")");
    }
    String owner = job.getOwnerUserId();
    return repository.findByOwnerUserIdAndJobId(owner, job.getId())
        .orElseGet(() -> repository.save(new DashboardCard(
            null, owner, job.getDataSourceId(), job.getId(),
            job.getQuestion(), engine, job.getResultJson(), Instant.now())));
  }

  /** The caller's cards, newest first. */
  public List<DashboardCard> list(String ownerUserId) {
    return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId);
  }

  /**
   * Removes an owned card. {@code false} when no such card exists for this
   * user (the api layer maps that to NotFound).
   */
  @Transactional
  public boolean remove(UUID cardId, String ownerUserId) {
    return repository.findByIdAndOwnerUserId(cardId, ownerUserId)
        .map(card -> {
          repository.delete(card);
          return true;
        })
        .orElse(false);
  }
}
