/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.scheduled;

import io.argos.sample.common.diag.JavaRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Execution counters of the three {@code @Scheduled} methods (GET /batch/scheduled), with a wait
 * until each of them has run at least once (the smoke and the agent tests need all three).
 */
@Component
public class ScheduledStats {

  private final Object lock = new Object();
  private long fixedRate;
  private long cron;
  private long longStarted;
  private long longCompleted;
  private boolean longActive;
  private long longStartedNanos;
  private long lastLongDurationMs = -1;
  private long lastLongSlices;
  private String lastThread;
  private Boolean lastVirtual;

  void fixedRateRan() {
    synchronized (lock) {
      fixedRate++;
      observeThread();
      lock.notifyAll();
    }
  }

  void cronRan() {
    synchronized (lock) {
      cron++;
      observeThread();
      lock.notifyAll();
    }
  }

  void longStarted() {
    synchronized (lock) {
      longStarted++;
      longActive = true;
      longStartedNanos = System.nanoTime();
      observeThread();
      lock.notifyAll();
    }
  }

  void longFinished(long slices, boolean completed) {
    synchronized (lock) {
      longActive = false;
      lastLongDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - longStartedNanos);
      lastLongSlices = slices;
      if (completed) {
        longCompleted++;
      }
      lock.notifyAll();
    }
  }

  /** Whether the last scheduled execution ran on a virtual thread (null before the first run). */
  public Boolean lastRunVirtual() {
    synchronized (lock) {
      return lastVirtual;
    }
  }

  /**
   * Waits up to {@code timeoutMs} until the fixed-rate, cron and long-running methods have each
   * started at least once, then returns the counters.
   */
  public Map<String, Object> await(long timeoutMs) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    synchronized (lock) {
      while (fixedRate == 0 || cron == 0 || longStarted == 0) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMs <= 0) {
          break;
        }
        lock.wait(remainingMs);
      }
      return snapshotLocked();
    }
  }

  public Map<String, Object> snapshot() {
    synchronized (lock) {
      return snapshotLocked();
    }
  }

  private Map<String, Object> snapshotLocked() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("fixedRate", fixedRate);
    result.put("cron", cron);
    result.put("longRunningStarted", longStarted);
    result.put("longRunningCompleted", longCompleted);
    result.put("longRunningActive", longActive);
    result.put(
        "longRunningElapsedMs",
        longActive ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - longStartedNanos) : 0);
    result.put("lastLongRunningDurationMs", lastLongDurationMs);
    result.put("lastLongRunningSlices", lastLongSlices);
    result.put("lastThread", lastThread);
    result.put("lastRunVirtual", lastVirtual);
    return result;
  }

  private void observeThread() {
    lastThread = Thread.currentThread().toString();
    lastVirtual = JavaRuntime.currentThreadIsVirtual();
  }
}
