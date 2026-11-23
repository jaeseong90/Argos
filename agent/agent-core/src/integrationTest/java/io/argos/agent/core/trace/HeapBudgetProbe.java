/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.stats.StatsAggregator;
import io.argos.agent.core.transport.AgentQueue;
import io.argos.agent.core.transport.QueueItem;
import io.argos.agent.core.transport.Queues;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * The forked JVM of {@code HeapBudgetIntegrationTest} (N-02 reduced, ADR-0009 C-87): fills every
 * queue of 10 §10.1, the dictionary and the service names to their caps with worst-case data
 * through the real tracer, then prints {@code key=value} lines: the heap growth after full GCs, the
 * {@code io.argos.agent.} instance bytes of {@code GC.class_histogram}, the queues' estimates and
 * the drop counters.
 */
public final class HeapBudgetProbe {
  private HeapBudgetProbe() {}

  /**
   * A synthetic item of the statistics, metric and active queues (fixed sizes at the queue caps).
   */
  static final class Synthetic implements QueueItem {
    final byte[] payload;

    Synthetic(int bytes) {
      payload = new byte[bytes];
    }

    @Override
    public long estimatedBytes() {
      return 16L + payload.length;
    }

    @Override
    public long createdNanos() {
      return System.nanoTime();
    }
  }

  public static void main(String[] args) throws Exception {
    Map<String, String> values = new HashMap<String, String>();
    values.put("sampling.profile.normal-rate", "0");
    AgentConfig config = AgentConfig.of(values);
    AgentCounters counters = new AgentCounters();
    TraceRuntime runtime = TraceRuntime.create(config, counters);
    TracerImpl tracer = runtime.tracerImpl();
    // Worst case: capture.user-id plain, so that every record keeps its 128-character user id
    // (setHttp applies the mode, SEC-32).
    tracer.setCapture(
        CaptureConfig.DEFAULT.toBuilder().userIdMode(CaptureConfig.USER_ID_PLAIN).build());
    Queues queues = runtime.queues();
    long baseline = usedAfterGc();

    // 1. The count bound: 30,000 fast transactions without a profile (records only).
    long ended = 0;
    long profiles = 0;
    for (int i = 0; i < 30_000; i++) {
      TraceContext ctx = tracer.begin(Kind.HTTP, "GET /fast", System.nanoTime());
      tracer.setHttp(ctx, "GET", "/fast/" + i, "10.0.0.1", null);
      int seq = tracer.stepStart(ctx, StepType.SQL, 1, 0, System.nanoTime());
      tracer.stepEnd(ctx, seq, 0, false, 1, System.nanoTime());
      tracer.end(ctx, 200, null, null, null, System.nanoTime());
      ended++;
    }
    out("queue_size_after_count_fill", queues.transaction().size());

    // 2. Worst-case profiles: 1,000 steps with distinct 4 KB details each, error -> captured.
    for (int t = 0; t < 6; t++) {
      TraceContext ctx = tracer.begin(Kind.HTTP, "GET /worst", System.nanoTime());
      for (int s = 0; s < 1_000; s++) {
        int seq = tracer.stepStart(ctx, StepType.SQL, s + 1, 0, System.nanoTime());
        tracer.stepDetail(ctx, seq, text('d', t * 1_000 + s, 4096));
        tracer.stepEnd(ctx, seq, 0, false, 1, System.nanoTime());
      }
      tracer.end(ctx, 500, "java.lang.IllegalStateException", null, null, System.nanoTime());
      ended++;
      profiles++;
    }

    // 3. Worst-case records: path 2 KB, error message 1 KB, user id 128 (byte bound).
    for (int i = 0; i < 8_000; i++) {
      TraceContext ctx = tracer.begin(Kind.HTTP, "GET /big", System.nanoTime());
      tracer.setHttp(ctx, "GET", "/" + text('p', i, 2047), "10.0.0.2", text('u', i, 128));
      tracer.end(
          ctx, 500, "java.lang.IllegalStateException", text('m', i, 1024), null, System.nanoTime());
      ended++;
      profiles++;
    }

    // 4. Statistics (60), metric (120) and active (1) queues with synthetic items.
    for (int i = 0; i < 70; i++) {
      queues.stats().offer(new Synthetic(64 * 1024));
    }
    for (int i = 0; i < 130; i++) {
      queues.metric().offer(new Synthetic(16 * 1024));
    }
    for (int i = 0; i < 3; i++) {
      queues.active().offer(new Synthetic(32 * 1024));
    }

    // 5. The dictionary to its 8 MB budget (frames of 200 characters).
    for (int i = 0; i < 60_000; i++) {
      tracer.dict(DictKind.FRAME, text('f', i, 200));
    }

    // 6. Service names to their 4 MiB byte bound (names of 1,024 characters, the longest kept).
    ServiceNames serviceNames = tracer.serviceNames();
    for (int i = 0; i < 3_000; i++) {
      serviceNames.admit(text('s', i, TracerImpl.MAX_SERVICE_NAME_CHARS));
    }

    // 7. Full statistics (WO-16, 10 §9.3, §14): the runtime's aggregator with every SQL and EXT key
    // of one window plus overflow, and a second aggregator with two full windows alive (the
    // current one and the one that waits for its flush), the worst case between two flushes.
    StatsAggregator stats = tracer.stats();
    fillStats(stats, 0);
    final long[] clock = {1_700_000_000_000L};
    StatsAggregator twoWindows =
        new StatsAggregator(
            StatsAggregator.DEFAULT_WINDOW_SECONDS,
            StatsAggregator.DEFAULT_MAX_SQL_KEYS,
            StatsAggregator.MAX_EXT_KEYS,
            new StatsAggregator.Clock() {
              @Override
              public long currentTimeMillis() {
                return clock[0];
              }
            });
    fillStats(twoWindows, 1);
    clock[0] += StatsAggregator.DEFAULT_WINDOW_SECONDS * 1000L;
    fillStats(twoWindows, 2);

    long used = usedAfterGc();
    out("heap_growth_bytes", used - baseline);
    out("histogram_agent_bytes", agentHistogramBytes());
    out("transactions_ended", ended);
    out("profiles_captured", profiles);
    for (AgentQueue<QueueItem> queue : queues.all()) {
      out("queue_size." + queue.name(), queue.size());
      out("queue_bytes." + queue.name(), queue.estimatedBytes());
      out("dropped." + queue.name(), counters.dropped(queue.name()));
    }
    out("profile_dropped_total", counters.profileDropped());
    out("dictionary_bytes", runtime.dictionary().estimatedBytes());
    out("dictionary_frames", runtime.dictionary().size(DictKind.FRAME));
    out("dictionary_overflow_frame", counters.dictionaryOverflow(DictKind.FRAME));
    out("service_names_bytes", serviceNames.estimatedBytes());
    out("service_names_overflow", serviceNames.overflowCount());
    out("stats_bytes", stats.estimatedBytes());
    out("stats_two_windows_bytes", twoWindows.estimatedBytes());
    out("stats_overflow_sql", twoWindows.sqlOverflow());
    out("stats_overflow_ext", twoWindows.extOverflow());
    out(
        "estimated_total_bytes",
        queues.estimatedBytes()
            + runtime.dictionary().estimatedBytes()
            + serviceNames.estimatedBytes()
            + stats.estimatedBytes()
            + twoWindows.estimatedBytes());
    // Keep everything reachable until after the measurement.
    out(
        "still_reachable",
        tracer.counters() == counters
                && queues.transaction().size() > 0
                && tracer.stats() == stats
                && twoWindows.windowSeconds() > 0
            ? 1
            : 0);
  }

  /**
   * Every SQL key of three transaction kinds up to {@code stats.max-keys} and every EXT key up to
   * its cap, plus 10% beyond the caps (overflow), with errors, fetches and failures.
   */
  private static void fillStats(StatsAggregator stats, int salt) {
    int[] kinds = {Kind.HTTP, Kind.BATCH, Kind.MESSAGE};
    int sqlKeys = StatsAggregator.DEFAULT_MAX_SQL_KEYS + StatsAggregator.DEFAULT_MAX_SQL_KEYS / 10;
    for (int i = 0; i < sqlKeys; i++) {
      int kind = kinds[i % kinds.length];
      long sqlId = salt * 1_000_000L + i + 1;
      stats.recordSql(kind, sqlId, 7, 1_000_000L + i, i % 13 == 0);
      stats.recordSqlFetch(kind, sqlId, 7, 50_000L, 10);
    }
    int extKeys = StatsAggregator.MAX_EXT_KEYS + StatsAggregator.MAX_EXT_KEYS / 10;
    for (int i = 0; i < extKeys; i++) {
      stats.recordExt(kinds[i % kinds.length], salt * 1_000_000L + i + 1, 2_000_000L + i, i % 5);
    }
  }

  /** A distinct text of {@code length} characters ({@code prefix}, the number, padding). */
  static String text(char prefix, int number, int length) {
    StringBuilder text = new StringBuilder(length);
    text.append(prefix).append(number).append('-');
    while (text.length() < length) {
      text.append((char) ('a' + (text.length() + number) % 26));
    }
    return text.toString();
  }

  private static long usedAfterGc() throws InterruptedException {
    for (int i = 0; i < 3; i++) {
      System.gc();
      Thread.sleep(100);
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  /** Bytes of {@code io.argos.agent.} instances in {@code GC.class_histogram} (C-87). */
  private static long agentHistogramBytes() throws Exception {
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    String histogram =
        (String)
            server.invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram",
                new Object[] {new String[0]},
                new String[] {String[].class.getName()});
    long bytes = 0;
    for (String line : histogram.split("\n", -1)) {
      String trimmed = line.trim();
      if (!trimmed.contains("io.argos.agent.")) {
        continue;
      }
      String[] parts = trimmed.split("\\s+", -1);
      if (parts.length >= 4) {
        String className = parts[3];
        if (className.startsWith("io.argos.agent.")) {
          bytes += Long.parseLong(parts[2]);
        }
      }
    }
    return bytes;
  }

  private static void out(String key, long value) {
    System.out.println(String.format(Locale.ROOT, "%s=%d", key, value));
  }
}
