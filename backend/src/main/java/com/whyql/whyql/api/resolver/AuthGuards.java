package com.whyql.whyql.api.resolver;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.whyql.whyql.jobs.service.JobService;

/**
 * Field-level auth checks for GraphQL resolvers. The /graphql endpoint itself
 * stays open (GraphiQL convenience), so every resolver declares what it
 * needs: a real, non-anonymous user here — and owner-only visibility for
 * anything user-scoped.
 */
public final class AuthGuards {

  private AuthGuards() {}

  public static void requireUser(Authentication auth) {
    if (auth == null || !auth.isAuthenticated()
        || auth instanceof AnonymousAuthenticationToken) {
      throw new UnauthorizedGraphQLException();
    }
  }

  /**
   * Loads a job and checks it belongs to the caller, so no user can observe
   * another user's job status or results.
   */
  public static com.whyql.whyql.jobs.domain.Job requireOwnJob(
      JobService jobService, java.util.UUID jobId, Authentication auth) {
    requireUser(auth);
    com.whyql.whyql.jobs.domain.Job job;
    try {
      job = jobService.findById(jobId);
    } catch (IllegalArgumentException notFound) {
      throw new NotFoundGraphQLException("Job not found: " + jobId);
    }
    if (job == null) {
      throw new NotFoundGraphQLException("Job not found: " + jobId);
    }
    if (!auth.getName().equals(job.getOwnerUserId())) {
      throw new ForbiddenGraphQLException(
          "Forbidden: job " + jobId + " belongs to another user");
    }
    return job;
  }
}
