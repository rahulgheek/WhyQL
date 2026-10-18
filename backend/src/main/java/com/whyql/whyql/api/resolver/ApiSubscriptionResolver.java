package com.whyql.whyql.api.resolver;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import com.whyql.whyql.api.graphql.WsAuthConfig;

import com.whyql.whyql.api.job.JobUpdateHub;
import com.whyql.whyql.api.model.Job;
import com.whyql.whyql.jobs.service.JobService;
import com.whyql.whyql.pipeline.service.AsyncQueryOrchestrator;

import reactor.core.publisher.Flux;

/**
 * Subscription resolvers: stream job stage events. Authorization mirrors the
 * job query (owner-scoped); the hub provides the initial snapshot plus every
 * subsequent transition until the job is terminal.
 */
@Controller
public class ApiSubscriptionResolver {

  private final JobService jobService;
  private final JobUpdateHub hub;

  public ApiSubscriptionResolver(JobService jobService, JobUpdateHub hub) {
    this.jobService = jobService;
    this.hub = hub;
  }

  @SubscriptionMapping
  public Flux<Job> jobUpdates(@Argument java.util.UUID jobId,
      @ContextValue(WsAuthConfig.AUTHENTICATION) Authentication auth) {
    com.whyql.whyql.jobs.domain.Job job = AuthGuards.requireOwnJob(jobService, jobId, auth);
    return hub.stream(job);
  }
}
