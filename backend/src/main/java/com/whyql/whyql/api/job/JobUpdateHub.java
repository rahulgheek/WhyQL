package com.whyql.whyql.api.job;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.whyql.whyql.api.model.Job;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.event.JobEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Bridges in-process job events (published by JobEventPublisher) to GraphQL
 * subscriptions. One multicast sink per watched jobId; the sink emits the
 * initial snapshot first and completes on the terminal status.
 *
 * Single-instance only for now — multi-node deployments will need the stage
 * events delivered through the Kafka-based transport to every node.
 */
@Component
public class JobUpdateHub {

  private static final Logger log = LoggerFactory.getLogger(JobUpdateHub.class);

  private final Map<UUID, Sinks.Many<Job>> sinks = new ConcurrentHashMap<>();
  private final JobMapper jobMapper;

  public JobUpdateHub(JobMapper jobMapper) {
    this.jobMapper = jobMapper;
  }

  /**
   * Flight path for one subscription: emits the job's current state
   * immediately (so polling between status changes is unnecessary), then
   * streams later stage events until the job reaches a terminal status.
   *
   * @param initial the already-authorized job snapshot (ownership checked by
   *     the resolver)
   */
  public Flux<Job> stream(com.whyql.whyql.jobs.domain.Job initial) {
    Job snapshot = jobMapper.toGql(initial);
    if (initial.isTerminal()) {
      return Flux.just(snapshot);
    }
    Sinks.Many<Job> sink = Sinks.many().unicast().onBackpressureBuffer();
    // Register BEFORE emitting so a racing event can't be missed.
    sinks.put(initial.getId(), sink);
    sink.tryEmitNext(snapshot);
    log.info("Subscribed to job {} updates", initial.getId());
    return sink.asFlux()
        .doFinally(signal -> {
          sinks.remove(initial.getId(), sink);
          log.info("Unsubscribed from job {} ({})", initial.getId(), signal);
        });
  }

  @EventListener
  public void onJobEvent(JobEvent event) {
    Sinks.Many<Job> sink = sinks.get(event.jobId());
    if (sink == null) {
      return; // nobody is watching this job locally
    }
    Job update = jobMapper.fromEvent(event);
    sink.tryEmitNext(update);
    if (update.status().equals(JobStatus.COMPLETED.name())
        || update.status().equals(JobStatus.FAILED.name())
        || update.status().equals(JobStatus.NEEDS_CLARIFICATION.name())) {
      sinks.remove(event.jobId(), sink);
      sink.tryEmitComplete();
    }
  }
}
