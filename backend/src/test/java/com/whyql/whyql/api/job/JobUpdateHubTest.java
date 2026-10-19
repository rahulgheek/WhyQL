package com.whyql.whyql.api.job;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.whyql.whyql.api.model.Job;
import com.whyql.whyql.jobs.domain.JobStatus;
import com.whyql.whyql.jobs.event.JobEvent;

import reactor.core.publisher.Flux;

/**
 * Unit tests for the subscription bridge: initial snapshot emission, stage
 * event streaming, and completion semantics.
 */
class JobUpdateHubTest {

  private JobUpdateHub hub;

  private com.whyql.whyql.jobs.domain.Job job;

  @BeforeEach
  void setUp() {
    hub = new JobUpdateHub(new JobMapper(new tools.jackson.databind.ObjectMapper()));

    job = new com.whyql.whyql.jobs.domain.Job();
    job.setId(UUID.randomUUID());
    job.setOwnerUserId("user-1");
    job.setDataSourceId(UUID.randomUUID());
    job.setQuestion("revenue by month");
    job.setStatus(JobStatus.GENERATING);
  }

  private JobEvent event(JobStatus status) {
    return new JobEvent(job.getId(), job.getOwnerUserId(), job.getDataSourceId(),
        job.getQuestion(), status, null, null, null, null, null, java.time.Instant.now());
  }

  @Test
  void terminalJobEmitsASingleSnapshotAndCompletes() {
    job.setStatus(JobStatus.FAILED);
    job.setErrorMessage("boom");

    Flux<Job> flux = hub.stream(job);

    List<Job> received = flux.collectList().block(java.time.Duration.ofSeconds(2));
    assertTrue(received != null && received.size() == 1);
    assertTrue("FAILED".equals(received.get(0).status()));
    assertTrue(received.get(0).errorMessage().contains("boom"));
  }

  @Test
  void streamDeliversSnapshotThenStageEventsUntilCompleted() throws Exception {
    Flux<Job> flux = hub.stream(job);

    List<Job> received = new ArrayList<>();
    CountDownLatch completed = new CountDownLatch(1);
    var subscription = flux.subscribe(received::add,
        error -> completed.countDown(), completed::countDown);

    // In-flight stages stream by...
    hub.onJobEvent(event(JobStatus.VALIDATING));
    hub.onJobEvent(event(JobStatus.EXECUTING));
    // ...and the terminal event closes the stream.
    hub.onJobEvent(event(JobStatus.COMPLETED));

    assertTrue(completed.await(2, TimeUnit.SECONDS), "stream did not complete");
    subscription.dispose();

    assertTrue(received.size() == 4,
        "expected [snapshot, validating, executing, completed], got " + received);
    assertTrue("GENERATING".equals(received.get(0).status()));
    assertTrue("VALIDATING".equals(received.get(1).status()));
    assertTrue(received.get(1).progress() == 50);
    assertTrue("EXECUTING".equals(received.get(2).status()));
    assertTrue("COMPLETED".equals(received.get(3).status()));
  }

  @Test
  void eventsForUnsubscribedJobsAreIgnored() {
    // No subscriber registered: must not throw anywhere.
    hub.onJobEvent(event(JobStatus.EXECUTING));

    var subscription = hub.stream(job).subscribe(j -> {});

    // And events for other jobs do not leak into this subscription.
    var other = UUID.randomUUID();
    hub.onJobEvent(new JobEvent(other, "x", UUID.randomUUID(), "q",
        JobStatus.FAILED, "boom", null, null, null, null, java.time.Instant.now()));

    subscription.dispose();
  }
}
