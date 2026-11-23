/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.api.dashboard.Values;
import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.ingest.api.MetricPoint;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Transaction counters of the {@code counter} and {@code overview} topics (12 §3.7 "실시간 TPS": by
 * {@code ts} buckets of 1 s, not by arrival). Points are added to the second of their {@code ts};
 * every 5 s the window {@code [end − 5 s, end)} of the 5-second grid is closed and its values are
 * computed per application and kind: {@code tps} (count / 5), {@code avg_ms}, {@code p95_ms},
 * {@code p99_ms} (nearest rank) and {@code error_rate} (6 decimals, like the REST dashboard's
 * {@code Values}).
 *
 * <p>A window closes {@value #GRACE_MS} ms after its end so that the points of the agents' last
 * batches arrive first. A point of a second that is already closed is late for the counters and is
 * excluded (counted); so is a point more than {@value #FUTURE_SECONDS} s ahead of the server clock.
 * Per second and kind at most {@value #MAX_SAMPLES} elapsed values are kept for the percentiles
 * (reservoir sampling beyond); counts, sums and errors are exact.
 *
 * <p><b>JVM values</b> (ADR-0010 C-212 (1)): the heap, GC pause and CPU samples of the metric
 * batches ({@link MetricPoint}) are added to the same seconds by their {@code ts} and closed with
 * the same window, with the definitions of the REST dashboard's {@code series} (14 §4.4, {@code
 * MetricKeys}) over the 5-second window: per instance the gauge average (value sum / samples), then
 * {@code heap_used} = the sum over the instances (bytes, 0 decimals), {@code cpu} = the mean over
 * the instances (4 decimals), and {@code gc_pause_ms} = the summed increase × 1000 (3 decimals). A
 * value without samples in the window is null, never 0 (D-23). Per second at most {@value
 * #MAX_JVM_INSTANCES} instances of an application are kept; samples of further instances, of closed
 * seconds and too far ahead are excluded ({@link #jvmExcluded()}).
 *
 * <p>Thread safety: every method is synchronized (the xlog thread adds, the periodic thread
 * closes).
 */
final class CounterAggregator {

  /** The grid (14 §5.3: {@code counter} every 5 s). */
  static final int WINDOW_SECONDS = 5;

  /** How long after its end a window closes. */
  static final long GRACE_MS = 2_000;

  /** Seconds ahead of the clock still accepted. */
  static final int FUTURE_SECONDS = 5;

  /** Elapsed samples per second and kind. */
  static final int MAX_SAMPLES = 20_000;

  /** Instances per second and application whose JVM samples are kept. */
  static final int MAX_JVM_INSTANCES = 1_000;

  /** Applications without points for this long are forgotten. */
  static final long IDLE_APP_MS = 10 * 60_000L;

  private static final int RING = 64;
  private static final int KINDS = 3;

  /** The values of one application and kind choice in a closed window. */
  record Stats(
      long count,
      long errors,
      Double tps,
      Double avgMs,
      Double p95Ms,
      Double p99Ms,
      Double errorRate) {

    /** A window without points: 0 TPS, the other values unknown. */
    static final Stats EMPTY =
        new Stats(0, 0, Values.tps(0, WINDOW_SECONDS), null, null, null, null);
  }

  /**
   * The JVM values of one application in a closed window (null: no sample, D-23).
   *
   * @param heapUsed bytes: the sum over the instances of their average
   * @param gcPauseMs the summed GC pause increase, milliseconds
   * @param cpu the mean over the instances of their average process CPU ratio
   */
  record Jvm(Double heapUsed, Double gcPauseMs, Double cpu) {

    /** A window without samples. */
    static final Jvm EMPTY = new Jvm(null, null, null);
  }

  /**
   * One closed window: its bounds, the transaction values of every application that had points and
   * the JVM values of every application that had samples.
   */
  record Window(
      long startMs, long endMs, Map<Integer, Map<TxKind, Stats>> apps, Map<Integer, Jvm> jvm) {

    /** The values of an application and kind choice ({@link Stats#EMPTY} without points). */
    Stats stats(int appId, TxKind kind) {
      Map<TxKind, Stats> m = apps.get(appId);
      Stats s = m == null ? null : m.get(kind);
      return s == null ? Stats.EMPTY : s;
    }

    /** The JVM values of an application ({@link Jvm#EMPTY} without samples). */
    Jvm jvm(int appId) {
      Jvm j = jvm.get(appId);
      return j == null ? Jvm.EMPTY : j;
    }
  }

  /** The JVM samples of one instance in one second (or merged over a window). */
  private static final class InstanceJvm {
    double heapSum;
    long heapN;
    double cpuSum;
    long cpuN;
    double gcIncrease;
    long gcN;

    void add(MetricPoint p) {
      switch (p.series()) {
        case HEAP_USED -> {
          heapSum += p.value();
          heapN++;
        }
        case CPU -> {
          cpuSum += p.value();
          cpuN++;
        }
        case GC_PAUSE -> {
          gcIncrease += p.value();
          gcN++;
        }
      }
    }

    void merge(InstanceJvm o) {
      heapSum += o.heapSum;
      heapN += o.heapN;
      cpuSum += o.cpuSum;
      cpuN += o.cpuN;
      gcIncrease += o.gcIncrease;
      gcN += o.gcN;
    }
  }

  private static final class Second {
    long epochSecond = Long.MIN_VALUE;
    final long[] count = new long[KINDS];
    final long[] errors = new long[KINDS];
    final long[] sum = new long[KINDS];
    final long[] seen = new long[KINDS];
    final int[][] samples = new int[KINDS][];
    final int[] sampleCount = new int[KINDS];

    /** JVM samples by instance, null without any. */
    Map<Integer, InstanceJvm> jvm;

    void reset(long sec) {
      epochSecond = sec;
      jvm = null;
      Arrays.fill(count, 0);
      Arrays.fill(errors, 0);
      Arrays.fill(sum, 0);
      Arrays.fill(seen, 0);
      Arrays.fill(sampleCount, 0);
    }
  }

  private static final class AppBuckets {
    final Second[] ring = new Second[RING];
    long lastPointMs;
  }

  private final Map<Integer, AppBuckets> apps = new HashMap<>();
  private final SplittableRandom random = new SplittableRandom(0x5eed);

  /** The first second that is not closed yet. */
  private long openFromSecond;

  private long late;
  private long future;
  private long jvmExcluded;

  /** An aggregator whose first window is the one open at {@code nowMs}. */
  CounterAggregator(long nowMs) {
    this.openFromSecond = closedThrough(nowMs) / 1000;
  }

  /** The end (ms) of the newest window that may close at {@code nowMs}. */
  static long closedThrough(long nowMs) {
    long gridMs = WINDOW_SECONDS * 1000L;
    return Math.floorDiv(nowMs - GRACE_MS, gridMs) * gridMs;
  }

  /**
   * Adds the points of one bundle (application {@code appId}).
   *
   * @return the points excluded (late for a closed second, or too far ahead)
   */
  synchronized int add(int appId, List<BusPoint> points, long nowMs) {
    int excluded = 0;
    long maxSecond = Math.floorDiv(nowMs, 1000) + FUTURE_SECONDS;
    AppBuckets b = null;
    for (BusPoint p : points) {
      long sec = Math.floorDiv(p.tsMs(), 1000);
      if (sec < openFromSecond) {
        late++;
        excluded++;
        continue;
      }
      if (sec > maxSecond || sec >= openFromSecond + RING) {
        future++;
        excluded++;
        continue;
      }
      int k = p.kind() - 1;
      if (k < 0 || k >= KINDS) {
        continue;
      }
      if (b == null) {
        b = apps.computeIfAbsent(appId, a -> new AppBuckets());
        b.lastPointMs = nowMs;
      }
      int slot = (int) Math.floorMod(sec, (long) RING);
      Second s = b.ring[slot];
      if (s == null) {
        s = new Second();
        b.ring[slot] = s;
      }
      if (s.epochSecond != sec) {
        s.reset(sec);
      }
      s.count[k]++;
      s.sum[k] += p.elapsedMs();
      if (p.error()) {
        s.errors[k]++;
      }
      sample(s, k, p.elapsedMs());
    }
    return excluded;
  }

  /**
   * Adds the JVM samples of one metric batch of instance {@code instanceId} of application {@code
   * appId}.
   *
   * @return the samples excluded (late for a closed second, too far ahead, or beyond {@value
   *     #MAX_JVM_INSTANCES} instances in their second)
   */
  synchronized int addMetrics(int appId, int instanceId, List<MetricPoint> points, long nowMs) {
    int excluded = 0;
    long maxSecond = Math.floorDiv(nowMs, 1000) + FUTURE_SECONDS;
    AppBuckets b = null;
    for (MetricPoint p : points) {
      long sec = Math.floorDiv(p.tsMs(), 1000);
      if (sec < openFromSecond || sec > maxSecond || sec >= openFromSecond + RING) {
        excluded++;
        continue;
      }
      if (b == null) {
        b = apps.computeIfAbsent(appId, a -> new AppBuckets());
        b.lastPointMs = nowMs;
      }
      int slot = (int) Math.floorMod(sec, (long) RING);
      Second s = b.ring[slot];
      if (s == null) {
        s = new Second();
        b.ring[slot] = s;
      }
      if (s.epochSecond != sec) {
        s.reset(sec);
      }
      if (s.jvm == null) {
        s.jvm = new HashMap<>();
      }
      InstanceJvm acc = s.jvm.get(instanceId);
      if (acc == null) {
        if (s.jvm.size() >= MAX_JVM_INSTANCES) {
          excluded++;
          continue;
        }
        acc = new InstanceJvm();
        s.jvm.put(instanceId, acc);
      }
      acc.add(p);
    }
    jvmExcluded += excluded;
    return excluded;
  }

  private void sample(Second s, int k, int elapsed) {
    long seen = ++s.seen[k];
    int[] arr = s.samples[k];
    if (s.sampleCount[k] < MAX_SAMPLES) {
      if (arr == null || arr.length == s.sampleCount[k]) {
        arr =
            Arrays.copyOf(
                arr == null ? new int[0] : arr,
                Math.min(MAX_SAMPLES, Math.max(64, s.sampleCount[k] * 2)));
        s.samples[k] = arr;
      }
      arr[s.sampleCount[k]++] = elapsed;
      return;
    }
    long r = random.nextLong(seen);
    if (r < MAX_SAMPLES) {
      arr[(int) r] = elapsed;
    }
  }

  /**
   * Closes the newest window that may close at {@code nowMs} and returns it, or null when it was
   * closed already. Seconds before the window (after a clock jump or a stalled bus) are discarded.
   */
  synchronized Window close(long nowMs) {
    long endMs = closedThrough(nowMs);
    long endSecond = endMs / 1000;
    if (endSecond <= openFromSecond) {
      return null;
    }
    long startSecond = endSecond - WINDOW_SECONDS;
    Map<Integer, Map<TxKind, Stats>> out = new LinkedHashMap<>();
    Map<Integer, Jvm> jvmOut = new LinkedHashMap<>();
    Iterator<Map.Entry<Integer, AppBuckets>> it = apps.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Integer, AppBuckets> e = it.next();
      AppBuckets b = e.getValue();
      Map<TxKind, Stats> stats = stats(b, startSecond, endSecond);
      if (!stats.isEmpty()) {
        out.put(e.getKey(), stats);
      }
      Jvm jvm = jvm(b, startSecond, endSecond);
      if (jvm != null) {
        jvmOut.put(e.getKey(), jvm);
      }
      for (int i = 0; i < RING; i++) {
        Second s = b.ring[i];
        if (s != null && s.epochSecond < endSecond) {
          s.epochSecond = Long.MIN_VALUE;
          s.jvm = null;
        }
      }
      if (nowMs - b.lastPointMs > IDLE_APP_MS) {
        it.remove();
      }
    }
    openFromSecond = endSecond;
    return new Window(startSecond * 1000, endMs, out, jvmOut);
  }

  /** The JVM values of the window, null without samples. */
  private static Jvm jvm(AppBuckets b, long startSecond, long endSecond) {
    Map<Integer, InstanceJvm> merged = null;
    for (long sec = startSecond; sec < endSecond; sec++) {
      Second s = b.ring[(int) Math.floorMod(sec, (long) RING)];
      if (s == null || s.epochSecond != sec || s.jvm == null) {
        continue;
      }
      if (merged == null) {
        merged = new HashMap<>();
      }
      for (Map.Entry<Integer, InstanceJvm> e : s.jvm.entrySet()) {
        merged.computeIfAbsent(e.getKey(), k -> new InstanceJvm()).merge(e.getValue());
      }
    }
    if (merged == null) {
      return null;
    }
    double heap = 0;
    boolean anyHeap = false;
    double cpu = 0;
    int cpuInstances = 0;
    double gc = 0;
    boolean anyGc = false;
    for (InstanceJvm i : merged.values()) {
      if (i.heapN > 0) {
        heap += i.heapSum / i.heapN;
        anyHeap = true;
      }
      if (i.cpuN > 0) {
        cpu += i.cpuSum / i.cpuN;
        cpuInstances++;
      }
      if (i.gcN > 0) {
        gc += i.gcIncrease;
        anyGc = true;
      }
    }
    return new Jvm(
        anyHeap ? Values.round(heap, 0) : null,
        anyGc ? Values.round(gc * 1000, 3) : null,
        cpuInstances > 0 ? Values.round(cpu / cpuInstances, 4) : null);
  }

  private static Map<TxKind, Stats> stats(AppBuckets b, long startSecond, long endSecond) {
    long[] count = new long[KINDS];
    long[] errors = new long[KINDS];
    long[] sum = new long[KINDS];
    int[][] samples = new int[KINDS][];
    int[] n = new int[KINDS];
    long total = 0;
    for (long sec = startSecond; sec < endSecond; sec++) {
      Second s = b.ring[(int) Math.floorMod(sec, (long) RING)];
      if (s == null || s.epochSecond != sec) {
        continue;
      }
      for (int k = 0; k < KINDS; k++) {
        count[k] += s.count[k];
        errors[k] += s.errors[k];
        sum[k] += s.sum[k];
        total += s.count[k];
        if (s.sampleCount[k] > 0) {
          int[] arr = samples[k] == null ? new int[0] : samples[k];
          int[] merged = Arrays.copyOf(arr, n[k] + s.sampleCount[k]);
          System.arraycopy(s.samples[k], 0, merged, n[k], s.sampleCount[k]);
          samples[k] = merged;
          n[k] += s.sampleCount[k];
        }
      }
    }
    if (total == 0) {
      return Map.of();
    }
    Map<TxKind, Stats> out = new EnumMap<>(TxKind.class);
    TxKind[] kinds = {TxKind.HTTP, TxKind.BATCH, TxKind.MESSAGE};
    long allCount = 0;
    long allErrors = 0;
    long allSum = 0;
    int allN = 0;
    for (int k = 0; k < KINDS; k++) {
      out.put(kinds[k], stats(count[k], errors[k], sum[k], samples[k], n[k]));
      allCount += count[k];
      allErrors += errors[k];
      allSum += sum[k];
      allN += n[k];
    }
    int[] all = new int[allN];
    int at = 0;
    for (int k = 0; k < KINDS; k++) {
      if (n[k] > 0) {
        System.arraycopy(samples[k], 0, all, at, n[k]);
        at += n[k];
      }
    }
    out.put(TxKind.ALL, stats(allCount, allErrors, allSum, all, allN));
    return out;
  }

  private static Stats stats(long count, long errors, long sum, int[] samples, int n) {
    if (count == 0) {
      return Stats.EMPTY;
    }
    int[] sorted = Arrays.copyOf(samples, n);
    Arrays.sort(sorted);
    return new Stats(
        count,
        errors,
        Values.tps(count, WINDOW_SECONDS),
        Values.ms((double) sum / count),
        rank(sorted, 0.95),
        rank(sorted, 0.99),
        Values.ratio(errors, count, 6));
  }

  /** The nearest-rank percentile of sorted values, null without values. */
  static Double rank(int[] sorted, double q) {
    if (sorted.length == 0) {
      return null;
    }
    int index = (int) Math.ceil(q * sorted.length) - 1;
    return (double) sorted[Math.max(0, Math.min(sorted.length - 1, index))];
  }

  /** Points excluded because their second was closed already. */
  synchronized long late() {
    return late;
  }

  /** Points excluded because they were too far ahead of the clock. */
  synchronized long future() {
    return future;
  }

  /** JVM samples excluded (closed second, too far ahead, instance bound). */
  synchronized long jvmExcluded() {
    return jvmExcluded;
  }

  /** Applications with buckets. */
  synchronized int appCount() {
    return apps.size();
  }
}
