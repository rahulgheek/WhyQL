package com.whyql.whyql.jobs.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.whyql.whyql.jobs.domain.Job;

/** Data-access for jobs. Internal to the Jobs module. */
@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

}
