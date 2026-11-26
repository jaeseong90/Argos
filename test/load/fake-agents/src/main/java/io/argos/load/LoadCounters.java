/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the generator sent, per app and in total: the reference values of the end check (D-13: the
 * transactions the server acknowledged are the ones that must be stored) and of the achieved rates.
 * Thread-safe.
 */
public final class LoadCounters {

  /** One counter. */
  public enum Counter {
    /** Transactions acknowledged (items of {@code ACK_OK} minus {@code dropped_items}). */
    TX_ACCEPTED,
    /** Transactions the server dropped by quota or a full WAL ({@code dropped_items}). */
    TX_DROPPED,
    /** Transactions of calls that failed after every retry (unknown whether stored). */
    TX_UNACKNOWLEDGED,
    /** Transactions generated as failed (1 %). */
    TX_ERRORS,
    /** Transactions held back and sent late. */
    TX_LATE,
    /** Transaction batches sent a second time with the same seq. */
    DUPLICATE_BATCHES,
    /** Those that the server answered with {@code ACK_DUPLICATE}. */
    DUPLICATE_ACKS,
    /** Profiles acknowledged. */
    PROFILES,
    /** Profile steps acknowledged. */
    STEPS,
    /** {@code SqlStat} rows acknowledged. */
    STAT_SQL,
    /** {@code ExtStat} rows acknowledged. */
    STAT_EXT,
    /** Metric samples acknowledged. */
    METRIC_SAMPLES,
    /** Batch calls. */
    CALLS,
    /** Batch calls answered with an error status (each retry counts). */
    CALLS_FAILED,
    /** Retries of a batch call. */
    RETRIES,
    /** Sessions opened again after the server ended one. */
    RECONNECTS,
    /** Acks with a throttle hint. */
    THROTTLES
  }

  private final Map<Counter, LongAdder> totals = new EnumMap<>(Counter.class);
  private final LongAdder[] acceptedByApp;
  private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
  private final AtomicInteger failureCount = new AtomicInteger();

  /** Counters for {@code apps} apps. */
  public LoadCounters(int apps) {
    for (Counter c : Counter.values()) {
      totals.put(c, new LongAdder());
    }
    acceptedByApp = new LongAdder[apps];
    for (int i = 0; i < apps; i++) {
      acceptedByApp[i] = new LongAdder();
    }
  }

  /** Adds {@code n} to {@code counter}. */
  public void add(Counter counter, long n) {
    totals.get(counter).add(n);
  }

  /** Adds {@code n} accepted transactions of app {@code appIndex}. */
  public void addAccepted(int appIndex, long n) {
    acceptedByApp[appIndex].add(n);
    totals.get(Counter.TX_ACCEPTED).add(n);
  }

  /** The current value of {@code counter}. */
  public long get(Counter counter) {
    return totals.get(counter).sum();
  }

  /** Accepted transactions of app {@code appIndex}. */
  public long accepted(int appIndex) {
    return acceptedByApp[appIndex].sum();
  }

  /** Records a failure description (the first 50 are kept). */
  public void failure(String description) {
    if (failureCount.incrementAndGet() <= 50) {
      failures.add(description);
    }
  }

  /** Failures recorded in total. */
  public int failureCount() {
    return failureCount.get();
  }

  /** The first failures. */
  public List<String> failures() {
    return Collections.unmodifiableList(new ArrayList<>(failures));
  }

  /** Every counter now. */
  public Snapshot snapshot() {
    Map<Counter, Long> values = new EnumMap<>(Counter.class);
    totals.forEach((k, v) -> values.put(k, v.sum()));
    long[] byApp = new long[acceptedByApp.length];
    for (int i = 0; i < byApp.length; i++) {
      byApp[i] = acceptedByApp[i].sum();
    }
    return new Snapshot(System.nanoTime(), values, byApp);
  }

  /** The counters at one moment. */
  public static final class Snapshot {
    private final long nanoTime;
    private final Map<Counter, Long> values;
    private final long[] acceptedByApp;

    /**
     * A snapshot.
     *
     * @param nanoTime {@link System#nanoTime()} of the snapshot
     * @param values every counter
     * @param acceptedByApp accepted transactions by app index
     */
    public Snapshot(long nanoTime, Map<Counter, Long> values, long[] acceptedByApp) {
      this.nanoTime = nanoTime;
      this.values = Collections.unmodifiableMap(new EnumMap<>(values));
      this.acceptedByApp = acceptedByApp.clone();
    }

    /** {@link System#nanoTime()} of the snapshot. */
    public long nanoTime() {
      return nanoTime;
    }

    /** One counter. */
    public long get(Counter counter) {
      return values.getOrDefault(counter, 0L);
    }

    /** Accepted transactions by app index. */
    public long[] acceptedByApp() {
      return acceptedByApp.clone();
    }

    /** The rate of {@code counter} per second between {@code earlier} and this snapshot. */
    public double ratePerSecond(Counter counter, Snapshot earlier) {
      double seconds = (nanoTime - earlier.nanoTime) / 1e9;
      return seconds <= 0 ? 0 : (get(counter) - earlier.get(counter)) / seconds;
    }

    @Override
    public String toString() {
      return "Snapshot" + values;
    }
  }
}
