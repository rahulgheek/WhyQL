package com.whyql.whyql.dashboard.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.whyql.whyql.dashboard.domain.DashboardCard;

public interface DashboardCardRepository extends JpaRepository<DashboardCard, UUID> {

  List<DashboardCard> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);

  Optional<DashboardCard> findByOwnerUserIdAndJobId(String ownerUserId, UUID jobId);

  Optional<DashboardCard> findByIdAndOwnerUserId(UUID id, String ownerUserId);
}
