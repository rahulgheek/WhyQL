package com.whyql.whyql.datasource.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.whyql.whyql.datasource.domain.DataSource;

@Repository
public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {

  Optional<DataSource> findByIdAndOwnerUserId(UUID id, String ownerUserId);

  List<DataSource> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);
}
