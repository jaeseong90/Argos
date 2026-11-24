/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.jobs;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Detached job runs ({@code POST /batch/run/{job}?detached=true}): each run gets its own plain
 * {@code java.lang.Thread} that the app creates and starts, outside any executor, so no agent
 * carries the request's transaction into it (10 §8: a plain thread is not propagated) and every
 * Spring Batch step of the run is an entry point of its own (10 §6.8). The last {@value #MAX_RUNS}
 * runs are kept for {@code GET /batch/executions/{id}}.
 */
@Component
public class DetachedRuns {

  /** Upper bound of kept runs (oldest dropped first). */
  public static final int MAX_RUNS = 32;

  /** Name prefix of the run threads. */
  public static final String THREAD_PREFIX = "shop-batch-detached-";

  /** State of one detached run. */
  private static final class Run {
    final long id;
    final String job;
    final CountDownLatch done = new CountDownLatch(1);
    volatile Map<String, Object> summary;
    volatile String failure;

    Run(long id, String job) {
      this.id = id;
      this.job = job;
    }
  }

  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, Run> runs = new LinkedHashMap<>();

  /** Starts {@code work} for job {@code job} on a new plain thread; returns the run id. */
  public long start(String job, Callable<Map<String, Object>> work) {
    Run run = new Run(ids.incrementAndGet(), job);
    synchronized (runs) {
      runs.put(run.id, run);
      Iterator<Long> oldest = runs.keySet().iterator();
      while (runs.size() > MAX_RUNS && oldest.hasNext()) {
        oldest.next();
        oldest.remove();
      }
    }
    Thread thread =
        new Thread(
            () -> {
              try {
                run.summary = work.call();
              } catch (Exception | Error e) {
                run.failure = e.getClass().getName() + ": " + e.getMessage();
              } finally {
                run.done.countDown();
              }
            },
            THREAD_PREFIX + run.id);
    thread.setDaemon(true);
    thread.start();
    return run.id;
  }

  /**
   * The state of run {@code id} ({@code "last"}: the newest run), waiting up to {@code awaitMs} for
   * it to finish; empty for an unknown run. {@code state} is RUNNING, DONE (with the job's
   * execution summary at the top level) or FAILED (with {@code failure}).
   */
  public Optional<Map<String, Object>> describe(String id, long awaitMs)
      throws InterruptedException {
    Run run = find(id);
    if (run == null) {
      return Optional.empty();
    }
    boolean finished = run.done.await(awaitMs, TimeUnit.MILLISECONDS);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("runId", run.id);
    result.put("job", run.job);
    result.put("detached", true);
    result.put("thread", THREAD_PREFIX + run.id);
    if (!finished) {
      result.put("state", "RUNNING");
    } else if (run.failure != null) {
      result.put("state", "FAILED");
      result.put("failure", run.failure);
    } else {
      result.put("state", "DONE");
      if (run.summary != null) {
        result.putAll(run.summary);
      }
    }
    return Optional.of(result);
  }

  private Run find(String id) {
    synchronized (runs) {
      if (id.equals("last")) {
        Run last = null;
        for (Run run : runs.values()) {
          last = run;
        }
        return last;
      }
      try {
        return runs.get(Long.parseLong(id));
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }
}
