/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.QueryFile.Section;
import io.argos.storage.queries.QueryLog;
import io.argos.storage.queries.SqlTemplate;
import io.argos.storage.queries.Stats;
import io.argos.storage.queries.Tsv;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Q17 concurrent load (N-06): {@code users} virtual users each run the variants named by Q17's
 * {@code @uses} (Q01/1h, Q03/10m, Q07/1d_live) every {@code interval} for {@code duration}, while a
 * monitor samples active parts per partition, DelayedInserts and memory every 10 s. The ingestion
 * side (replay at the target TPS, ingestion delay p99) is the generator's; run it at the same time
 * for the Gate 0 measurement.
 *
 * <p>Query window ({@link Window}): during a replay the users must read the parts being written, so
 * in {@link Window#LIVE} mode every iteration of a user sets {@code end_minute} to the server's
 * current minute ({@code toStartOfMinute(now())}, refreshed at most once per second and shared by
 * the users), as a dashboard of the last hour does. {@link Window#FIXED} keeps the {@code
 * end_minute} of the loaded data (params.sql), for a run without ingestion.
 */
public final class ConcurrentLoad {

  private ConcurrentLoad() {}

  /** Where the query windows of the virtual users end. */
  public enum Window {
    /** {@code end_minute} = the server's current minute, renewed every iteration (replay). */
    LIVE,
    /** {@code end_minute} of the loaded data, resolved once (no ingestion). */
    FIXED;

    /** {@code live} or {@code fixed}. */
    public static Window parse(String text) {
      return valueOf(text.strip().toUpperCase(Locale.ROOT));
    }
  }

  /**
   * Options.
   *
   * @param users virtual users (13 §15: 10)
   * @param interval polling interval per user (5 s)
   * @param duration how long the users run (30 minutes)
   * @param sampleInterval monitor interval
   * @param ingestion what writes meanwhile (report text)
   * @param window where the query windows end
   */
  public record Options(
      int users,
      Duration interval,
      Duration duration,
      Duration sampleInterval,
      String ingestion,
      Window window) {

    /**
     * 13 §15 values: 10 users, 5 s, 30 minutes, monitor every 10 s; live windows when something is
     * ingested meanwhile ({@code ingestion} other than {@code none}), else fixed.
     */
    public static Options bench(String ingestion) {
      return new Options(
          10,
          Duration.ofSeconds(5),
          Duration.ofMinutes(30),
          Duration.ofSeconds(10),
          ingestion,
          defaultWindow(ingestion));
    }

    /** {@link Window#LIVE} during an ingestion, {@link Window#FIXED} for {@code none}. */
    public static Window defaultWindow(String ingestion) {
      return ingestion == null || ingestion.isBlank() || ingestion.strip().startsWith("none")
          ? Window.FIXED
          : Window.LIVE;
    }
  }

  /**
   * The shared {@code end_minute} of the live window: the server's current minute, asked at most
   * once per {@code refresh} (a failed request keeps the previous value).
   */
  static final class LiveWindow {
    private final Supplier<String> serverMinute;
    private final Duration refresh;
    private final LongSupplier nanoTime;
    private final Set<String> seen = Collections.synchronizedSet(new TreeSet<>());
    private final AtomicLong failures = new AtomicLong();
    private String current;
    private long refreshedAt;

    /**
     * @param serverMinute the server's current minute ({@code SELECT toStartOfMinute(now())})
     * @param refresh how long a value is reused
     * @param nanoTime clock ({@link System#nanoTime} outside tests)
     */
    LiveWindow(Supplier<String> serverMinute, Duration refresh, LongSupplier nanoTime) {
      this.serverMinute = serverMinute;
      this.refresh = refresh;
      this.nanoTime = nanoTime;
    }

    /** The current {@code end_minute}. */
    synchronized String endMinute() {
      long now = nanoTime.getAsLong();
      if (current == null || now - refreshedAt >= refresh.toNanos()) {
        try {
          current = serverMinute.get();
          refreshedAt = now;
        } catch (RuntimeException e) {
          failures.incrementAndGet();
          if (current == null) {
            throw e;
          }
        }
      }
      seen.add(current);
      return current;
    }

    /** {@code params} with the current {@code end_minute}. */
    Parameters apply(Parameters params) {
      return params.with("end_minute", endMinute());
    }

    /** Distinct end minutes used so far, in order. */
    List<String> seen() {
      synchronized (seen) {
        return List.copyOf(seen);
      }
    }

    long failures() {
      return failures.get();
    }
  }

  /** Runs Q17. */
  public static ProcedureResult run(BenchContext ctx, Options o) throws InterruptedException {
    QueryFile f = ctx.file("Q17");
    List<Use> uses = new ArrayList<>();
    for (String use : f.uses()) {
      String[] parts = use.split("/", -1);
      QueryFile qf = ctx.file(parts[0]);
      Section v = qf.variant(parts[1]);
      Parameters p = ctx.parametersOf(parts[0]).with(v.params());
      Map<String, String> settings = new LinkedHashMap<>(qf.settings());
      settings.putAll(v.settings());
      uses.add(
          new Use(use, SqlTemplate.render(v.sql(), ctx.runner().textual(Map.of())), p, settings));
    }
    LiveWindow live =
        o.window() == Window.LIVE
            ? new LiveWindow(() -> serverMinute(ctx, f), Duration.ofSeconds(1), System::nanoTime)
            : null;
    Map<String, List<Double>> latencies = new LinkedHashMap<>();
    Map<String, List<String>> queryIds = new LinkedHashMap<>();
    for (Use u : uses) {
      latencies.put(u.label(), Collections.synchronizedList(new ArrayList<>()));
      queryIds.put(u.label(), Collections.synchronizedList(new ArrayList<>()));
    }
    AtomicLong errors = new AtomicLong();
    long start = System.nanoTime();
    long end = start + o.duration().toNanos();
    List<Thread> threads = new ArrayList<>();
    for (int user = 0; user < o.users(); user++) {
      long offset = o.interval().toNanos() * user / Math.max(1, o.users());
      Thread t =
          new Thread(
              () -> {
                long next = start + offset;
                while (next < end) {
                  sleepUntil(next);
                  for (Use u : uses) {
                    Parameters p;
                    try {
                      p = live == null ? u.params() : live.apply(u.params());
                    } catch (RuntimeException e) {
                      errors.incrementAndGet();
                      continue;
                    }
                    Measurement m =
                        ctx.runner()
                            .execute(
                                Account.READER,
                                u.sql(),
                                u.settings(),
                                p,
                                "Q17/" + u.label(),
                                "user",
                                false);
                    if (m.failed()) {
                      errors.incrementAndGet();
                    } else {
                      latencies.get(u.label()).add(m.wallMs());
                      queryIds.get(u.label()).add(m.queryId());
                    }
                  }
                  next += o.interval().toNanos();
                }
              },
              "q17-user-" + user);
      threads.add(t);
      t.start();
    }
    Parameters monitor = ctx.runner().baseParameters();
    long maxParts = 0;
    String maxPartsWhere = "";
    long memoryPeak = 0;
    long residentPeak = 0;
    long[] eventsStart = insertEvents(ctx, f, monitor);
    long delayedStart = eventsStart[0];
    long rejectedStart = eventsStart[1];
    long insertedStart = eventsStart[3];
    List<List<String>> samples = new ArrayList<>();
    while (System.nanoTime() < end) {
      List<List<String>> parts =
          Tsv.rows(ctx.runner().stepOk(f, "parts_per_partition", monitor, Map.of()).body());
      List<List<String>> mem = Tsv.rows(ctx.runner().stepOk(f, "memory", monitor, Map.of()).body());
      long p = parts.isEmpty() ? 0 : Long.parseLong(parts.get(0).get(2));
      if (p > maxParts) {
        maxParts = p;
        maxPartsWhere = parts.get(0).get(0) + " " + parts.get(0).get(1);
      }
      long tracked = Long.parseLong(mem.get(0).get(0));
      long resident = Long.parseLong(mem.get(0).get(1));
      memoryPeak = Math.max(memoryPeak, tracked);
      residentPeak = Math.max(residentPeak, resident);
      samples.add(
          List.of(
              String.format(Locale.ROOT, "%.0f", (System.nanoTime() - start) / 1e9),
              Long.toString(p),
              mib(tracked),
              mib(resident),
              mem.get(0).get(2),
              mem.get(0).get(3)));
      sleepUntil(Math.min(end, System.nanoTime() + o.sampleInterval().toNanos()));
    }
    for (Thread t : threads) {
      t.join();
    }
    long finished = System.nanoTime();
    long[] events = insertEvents(ctx, f, monitor);
    List<String> allIds = new ArrayList<>();
    queryIds.values().forEach(allIds::addAll);
    Map<String, QueryLog.Entry> log = ctx.runner().collect(allIds);
    List<Double> all = new ArrayList<>();
    List<List<String>> perQuery = new ArrayList<>();
    for (Map.Entry<String, List<Double>> e : latencies.entrySet()) {
      List<Double> l = new ArrayList<>(e.getValue());
      all.addAll(l);
      List<Double> readRows = new ArrayList<>();
      for (String id : new ArrayList<>(queryIds.get(e.getKey()))) {
        QueryLog.Entry entry = log.get(id);
        if (entry != null) {
          readRows.add((double) entry.readRows());
        }
      }
      perQuery.add(
          List.of(
              e.getKey(),
              Integer.toString(l.size()),
              ms(Stats.percentile(l, 0.5)),
              ms(Stats.percentile(l, 0.95)),
              ms(Stats.max(l)),
              readRows.isEmpty() ? "-" : String.format(Locale.ROOT, "%.0f", Stats.median(readRows)),
              readRows.isEmpty() ? "-" : String.format(Locale.ROOT, "%.0f", Stats.max(readRows))));
    }
    List<String> windows = live == null ? List.of() : live.seen();
    String windowText =
        live == null
            ? "fixed: end_minute " + ctx.shared().get("end_minute") + " of the loaded data"
            : "live: end_minute = toStartOfMinute(now()) of the server, renewed every iteration"
                + " (at most once per second); "
                + windows.size()
                + " distinct end minutes "
                + (windows.isEmpty()
                    ? ""
                    : windows.get(0) + " .. " + windows.get(windows.size() - 1))
                + (live.failures() == 0 ? "" : ", " + live.failures() + " failed refreshes");
    double cpu = ctx.runner().cpuLoad(start, finished);
    double cpuMax =
        ctx.runner().load() == null ? Double.NaN : ctx.runner().load().max(start, finished);
    return new ProcedureResult("Q17", f.title(), f.criterion())
        .metric("query_p95_ms", Stats.percentile(all, 0.95))
        .metric("parts_per_partition", maxParts)
        .metric("delayed_inserts", events[0] - delayedStart)
        .metric("memory_peak_bytes", memoryPeak)
        .metric("resident_peak_bytes", residentPeak)
        .metric("query_errors", errors.get())
        .metric("rejected_inserts", events[1] - rejectedStart)
        .metric("inserted_rows", events[3] - insertedStart)
        .metric("window_end_minutes", windows.size())
        .metric("machine_cpu_mean", cpu)
        .metric("machine_cpu_max", cpuMax)
        .table(
            new Table(
                "Query latency per virtual-user query (client wall time) and rows read"
                    + " (system.query_log)",
                List.of(
                    "query",
                    "runs",
                    "p50 ms",
                    "p95 ms",
                    "max ms",
                    "rows read (median)",
                    "rows read (max)"),
                perQuery))
        .table(
            new Table(
                "Monitor samples",
                List.of(
                    "t (s)",
                    "max parts/partition",
                    "MemoryTracking MiB",
                    "RSS MiB",
                    "merges",
                    "PartsActive"),
                samples))
        .note(
            o.users()
                + " users x "
                + uses.size()
                + " queries every "
                + o.interval().toSeconds()
                + " s for "
                + o.duration().toSeconds()
                + " s; ingestion meanwhile: "
                + o.ingestion()
                + "; window "
                + windowText
                + "; partition with most parts: "
                + (maxPartsWhere.isEmpty() ? "-" : maxPartsWhere)
                + "; query errors "
                + errors.get()
                + "; machine CPU mean / max "
                + pct(cpu)
                + " / "
                + pct(cpuMax))
        .judge();
  }

  private static String serverMinute(BenchContext ctx, QueryFile f) {
    // Not a recorded step: asked about once per second for the whole run.
    Measurement m =
        ctx.runner()
            .execute(
                Account.READER,
                SqlTemplate.render(f.step("server_minute").sql(), ctx.runner().textual(Map.of())),
                f.settings(),
                Parameters.empty(),
                "Q17/server_minute",
                "once",
                true);
    if (m.failed()) {
      throw new IllegalStateException("server_minute: " + m.errorName() + " " + m.errorMessage());
    }
    return Tsv.rows(m.body()).get(0).get(0);
  }

  private static long[] insertEvents(BenchContext ctx, QueryFile f, Parameters p) {
    List<String> r = Tsv.rows(ctx.runner().stepOk(f, "insert_events", p, Map.of()).body()).get(0);
    return new long[] {
      Long.parseLong(r.get(0)),
      Long.parseLong(r.get(1)),
      Long.parseLong(r.get(2)),
      Long.parseLong(r.get(3))
    };
  }

  private static void sleepUntil(long nanoTime) {
    long wait = nanoTime - System.nanoTime();
    if (wait > 0) {
      try {
        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static String ms(double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v);
  }

  private static String mib(long bytes) {
    return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
  }

  private static String pct(double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.0f %%", v * 100);
  }

  private record Use(String label, String sql, Parameters params, Map<String, String> settings) {}
}
