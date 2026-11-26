/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.test.soak.SeriesMath.Point;
import io.argos.test.soak.SoakSamples.AgentSample;
import io.argos.test.soak.SoakSamples.Segment;
import io.argos.test.soak.SoakSamples.StackSample;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The verdicts of a soak run (18 §11.2, T-SOAK-001, T-NFR-18).
 *
 * <p>Two kinds of criteria:
 *
 * <ul>
 *   <li>Invariants (OOM 0, the nightly consistency check, the ClickHouse settings rule): PASS or
 *       FAIL in every run; one that could not be checked is a FAIL.
 *   <li>Time and resource criteria (heap slope and maximum, NMT, threads, FD, server RSS, WAL,
 *       parts, disk growth, alert false positives, p99): PASS or FAIL only for a valid run ({@link
 *       SoakOptions#invalidReason()} empty: 72 hours, 500 TPS, dedicated machine); any other run
 *       reports them INVALID with the value and what it would have been. A criterion without enough
 *       points is INVALID ("not measured"): never a PASS without numbers.
 * </ul>
 *
 * <p>Two criteria follow ADR-0010 C-259 (4)(5) instead of the first wording of 18 §11.2: the WAL is
 * judged by its unloaded backlog (write position minus the load-acknowledged position, {@link
 * WalBacklog}) below 100 MB, with its size on disk at most {@code argos.wal.segment-bytes} + 100 MB
 * as a secondary condition (the WAL rotates at 256 MiB, so "WAL below 100 MB" cannot hold in normal
 * operation); the agent heap is the {@code io.argos.} classes of the histogram plus the agent's own
 * buffer estimate (queues, dictionary, service names), with the app's live heap as a reference.
 */
public final class SoakVerdicts {

  /** A verdict. */
  public enum Verdict {
    PASS,
    FAIL,
    INVALID
  }

  /**
   * One row of the report.
   *
   * @param id test id (18 appendix A)
   * @param requirement the requirement it proves
   * @param title what is judged
   * @param limit the limit of 18 §11.2
   * @param measured the measured value
   * @param invariant always PASS/FAIL
   * @param verdict the verdict
   * @param reason why (INVALID reasons, the failing points)
   */
  public record Criterion(
      String id,
      String requirement,
      String title,
      String limit,
      String measured,
      boolean invariant,
      Verdict verdict,
      String reason) {}

  /**
   * One nightly consistency check ({@code argos-admin consistency check --date <d> --json}).
   *
   * @param day the UTC day
   * @param exitCode the command's exit code (0: no rollup mismatch left)
   * @param result {@code result} of the JSON
   * @param rollupMismatchMinutes mismatching minutes of the D-03 and rollup checks
   * @param d12MismatchMinutes mismatching minutes of D-12 (recorded only, 12 §9.1)
   * @param error why the check did not run, or null
   */
  public record ConsistencyRun(
      LocalDate day,
      int exitCode,
      String result,
      long rollupMismatchMinutes,
      long d12MismatchMinutes,
      String error) {

    /** No rollup mismatch and no failure. */
    public boolean clean() {
      return error == null && exitCode == 0 && rollupMismatchMinutes == 0;
    }
  }

  /**
   * A FIRING event of the run (GET /api/v1/alerts/events).
   *
   * @param ts event time
   * @param app application name (or {@code server})
   * @param metric the rule's metric
   * @param severity warn or critical
   * @param value the value that fired, or NaN
   * @param threshold the threshold, or NaN
   * @param expectedByLoad true when the load meets the rule's condition by design (the bench mix's
   *     intended 5% of 500 answers against the {@code error_rate} WARN threshold of 5%): not a
   *     false positive
   */
  public record Firing(
      String ts,
      String app,
      String metric,
      String severity,
      double value,
      double threshold,
      boolean expectedByLoad) {}

  /**
   * Everything a verdict needs.
   *
   * @param agent hourly agent samples
   * @param stack 10-minute stack samples
   * @param segments load segments (hourly p99)
   * @param consistency the consistency checks (null: not run)
   * @param firing FIRING events (null: not read)
   * @param oomFindings OOM findings (null: not checked)
   * @param settingsViolations ClickHouse settings violations (null: not read)
   * @param serverHeapBytes the server's {@code -Xmx}
   * @param walSegmentBytes the server's {@code argos.wal.segment-bytes}
   * @param invalidReason why time and resource verdicts are INVALID (null: valid run)
   */
  public record Inputs(
      List<AgentSample> agent,
      List<StackSample> stack,
      List<Segment> segments,
      List<ConsistencyRun> consistency,
      List<Firing> firing,
      List<String> oomFindings,
      List<String> settingsViolations,
      long serverHeapBytes,
      long walSegmentBytes,
      String invalidReason) {}

  static final long KIB = 1024;
  static final long MIB = 1024 * KIB;
  static final long GIB = 1024 * MIB;

  /** 18 §11.2: heap slope at most +50 KB/h. */
  public static final double HEAP_SLOPE_LIMIT = 50.0 * KIB;

  /** 18 §11.2: agent heap at most 64 MB. */
  public static final long HEAP_MAX = 64 * MIB;

  /** 18 §11.2: NMT Internal+Other+Thread growth at most 16 MB (in KB). */
  public static final long NMT_GROWTH_KB = 16 * 1024;

  /** 18 §11.2: agent threads flat (±0). */
  public static final long THREAD_TOLERANCE = 0;

  /** 18 §11.2: FD flat (±5). */
  public static final long FD_TOLERANCE = 5;

  /**
   * 18 §11.2 with ADR-0010 C-259 (4): the unloaded WAL backlog below 100 MB in normal operation;
   * the WAL's size at most {@code segment-bytes} + this.
   */
  public static final long WAL_LIMIT = 100 * MIB;

  /** {@code argos.wal.segment-bytes} of the server (12 §3.4 default; the soak does not set it). */
  public static final long DEFAULT_WAL_SEGMENT_BYTES = 256 * MIB;

  /** The decision behind the WAL and agent heap criteria. */
  public static final String CRITERIA_DECISION = "ADR-0010 C-259 (4)(5)";

  /** 18 §11.2 and N-06: active parts per partition below 300. */
  public static final long PARTS_LIMIT = 300;

  /** Server resident set: heap plus 1 GiB (as the server load run, WO-29). */
  public static final long RSS_MARGIN = GIB;

  private SoakVerdicts() {}

  /** Every criterion of the run, in report order. */
  public static List<Criterion> evaluate(Inputs in) {
    List<Criterion> c = new ArrayList<>();
    Judge j = new Judge(in.invalidReason());
    List<Point> heap = series(in.agent(), AgentSample::agentBytes);
    double slope = SeriesMath.slopePerHour(heap);
    c.add(
        j.resource(
            "T-SOAK-001",
            "N-02",
            "agent heap slope (io.argos. classes after GC.run plus the agent's buffer estimate,"
                + " linear regression; "
                + CRITERIA_DECISION
                + ")",
            "<= +50 KB/h",
            heap.size() < 2
                ? null
                : signed(slope / KIB)
                    + " KB/h over "
                    + heap.size()
                    + " points"
                    + skipped(in.agent().size(), heap.size()),
            slope <= HEAP_SLOPE_LIMIT));
    double heapMax = SeriesMath.max(heap);
    c.add(
        j.resource(
            "T-SOAK-001",
            "N-02",
            "agent heap maximum (io.argos. classes plus the agent's buffer estimate; "
                + CRITERIA_DECISION
                + ")",
            "<= 64 MB",
            heap.isEmpty() ? null : mib(heapMax) + heapParts(in.agent()),
            heapMax <= HEAP_MAX));
    List<Point> nmt = series(in.agent(), AgentSample::nmtKb);
    double nmtGrowth = SeriesMath.growth(nmt);
    c.add(
        j.resource(
            "T-SOAK-001",
            "N-02",
            "NMT Internal+Other+Thread growth",
            "<= 16 MB",
            nmt.size() < 2
                ? null
                : mib(nmtGrowth * KIB)
                    + " (first "
                    + mib(nmt.get(0).value() * KIB)
                    + ", max "
                    + mib(SeriesMath.max(nmt) * KIB)
                    + ")",
            nmtGrowth <= NMT_GROWTH_KB));
    List<Point> threads = series(in.agent(), AgentSample::agentThreads);
    c.add(
        j.resource(
            "T-SOAK-001",
            "N-02",
            "agent threads (argos-*) flat",
            "±0",
            threads.size() < 2
                ? null
                : flatText(threads) + jvmThreadsText(series(in.agent(), AgentSample::jvmThreads)),
            SeriesMath.flat(threads, THREAD_TOLERANCE)));
    List<Point> fds = stackSeries(in.stack(), StackSample::appFds);
    c.add(
        j.resource(
            "T-SOAK-001",
            "N-02",
            "app file descriptors flat",
            "±5",
            fds.size() < 2 ? null : flatText(fds),
            SeriesMath.flat(fds, FD_TOLERANCE)));
    List<Point> rss = stackSeries(in.stack(), StackSample::serverRssBytes);
    double rssMax = SeriesMath.max(rss);
    long rssLimit = in.serverHeapBytes() + RSS_MARGIN;
    c.add(
        j.resource(
            "T-SOAK-001",
            "N-18",
            "server resident set within its budget",
            "<= -Xmx + 1 GiB (" + mib((double) rssLimit) + ")",
            rss.isEmpty() ? null : "max " + mib(rssMax),
            rssMax <= rssLimit));
    List<Point> backlog = stackSeries(in.stack(), StackSample::walBacklogBytes);
    double backlogMax = SeriesMath.max(backlog);
    c.add(
        j.resource(
            "T-SOAK-001",
            "D-13",
            "WAL unloaded backlog in normal operation (write position minus load-acknowledged"
                + " position; "
                + CRITERIA_DECISION
                + ")",
            "< 100 MB",
            backlog.isEmpty()
                ? null
                : "max " + mib(backlogMax) + " over " + backlog.size() + " points",
            backlogMax < WAL_LIMIT));
    List<Point> wal = stackSeries(in.stack(), StackSample::walBytes);
    double walMax = SeriesMath.max(wal);
    long walSizeLimit = in.walSegmentBytes() + WAL_LIMIT;
    c.add(
        j.resource(
            "T-SOAK-001",
            "D-13",
            "WAL size on disk, secondary condition (" + CRITERIA_DECISION + ")",
            "<= segment-bytes + 100 MB (" + mib((double) walSizeLimit) + ")",
            wal.isEmpty() ? null : "max " + mib(walMax),
            walMax <= walSizeLimit));
    List<Point> parts = stackSeries(in.stack(), StackSample::maxPartsPerPartition);
    double partsMax = SeriesMath.max(parts);
    c.add(
        j.resource(
            "T-SOAK-001",
            "N-18",
            "ClickHouse active parts per partition",
            "< 300",
            parts.isEmpty() ? null : "max " + Math.round(partsMax) + busiest(in.stack()),
            partsMax < PARTS_LIMIT));
    c.add(disk(j, in.stack()));
    c.add(consistency(in.consistency()));
    c.add(falsePositives(j, in.firing()));
    c.add(p99(j, in.segments()));
    c.add(
        Judge.invariant(
            "T-NFR-18",
            "N-18",
            "OOM 0 (server, apps, agent, ClickHouse, containers, kernel)",
            "0 findings",
            in.oomFindings() == null
                ? null
                : in.oomFindings().isEmpty()
                    ? "0"
                    : in.oomFindings().size() + ": " + String.join("; ", in.oomFindings()),
            in.oomFindings() != null && in.oomFindings().isEmpty()));
    c.add(
        Judge.invariant(
            "T-NFR-18",
            "N-18",
            "ClickHouse memory settings for the container limit (ClickHouseSettingsRule)",
            "no violation",
            in.settingsViolations() == null
                ? null
                : in.settingsViolations().isEmpty()
                    ? "none"
                    : String.join("; ", in.settingsViolations()),
            in.settingsViolations() != null && in.settingsViolations().isEmpty()));
    return c;
  }

  /** True when a criterion failed (the task's exit code). */
  public static boolean fails(List<Criterion> criteria) {
    return criteria.stream().anyMatch(c -> c.verdict() == Verdict.FAIL);
  }

  private static Criterion disk(Judge j, List<StackSample> stack) {
    List<StackSample> withTables = stack.stream().filter(s -> !s.bytesByTable().isEmpty()).toList();
    if (withTables.size() < 2) {
      return j.resource(
          "T-SOAK-001", "N-19", "disk growth against the N-19 formula", "±30%", null, false);
    }
    StackSample first = withTables.get(0);
    StackSample last = withTables.get(withTables.size() - 1);
    double seconds = last.seconds() - first.seconds();
    double actual =
        (double)
            (CapacityFormula.formulaBytes(last.bytesByTable())
                - CapacityFormula.formulaBytes(first.bytesByTable()));
    double expected =
        CapacityFormula.expectedBytes(
            CapacityFormula.growth(first.rowsByTable(), last.rowsByTable()));
    double ratio = CapacityFormula.ratio(actual, expected);
    String measured =
        String.format(
            Locale.ROOT,
            "%s/day measured, %s/day by the formula (ratio %.2f over %s)",
            gb(CapacityFormula.perDay(actual, seconds)),
            gb(CapacityFormula.perDay(expected, seconds)),
            ratio,
            SoakOptions.format(java.time.Duration.ofSeconds((long) seconds)));
    return j.resource(
        "T-SOAK-001",
        "N-19",
        "disk growth against the N-19 formula (13 §14 bytes per row)",
        "ratio 0.70..1.30",
        Double.isNaN(ratio) ? null : measured,
        CapacityFormula.withinTolerance(ratio));
  }

  private static Criterion consistency(List<ConsistencyRun> runs) {
    String measured = null;
    boolean ok = false;
    if (runs != null && !runs.isEmpty()) {
      List<String> parts = new ArrayList<>();
      for (ConsistencyRun r : runs) {
        parts.add(
            r.day()
                + ": "
                + (r.error() != null
                    ? "not run (" + r.error() + ")"
                    : "exit "
                        + r.exitCode()
                        + ", "
                        + r.result()
                        + ", rollup mismatch "
                        + r.rollupMismatchMinutes()
                        + " min, D-12 "
                        + r.d12MismatchMinutes()
                        + " min (recorded only)"));
      }
      measured = String.join("; ", parts);
      ok = runs.stream().allMatch(ConsistencyRun::clean);
    }
    return Judge.invariant(
        "T-SOAK-001",
        "F-S22",
        "nightly consistency check (argos-admin consistency check)",
        "mismatch 0",
        measured,
        ok);
  }

  private static Criterion falsePositives(Judge j, List<Firing> firing) {
    if (firing == null) {
      return j.resource(
          "T-SOAK-001", "F-S26", "alert false positives (template rules)", "0", null, false);
    }
    long expected = firing.stream().filter(Firing::expectedByLoad).count();
    List<Firing> fp = firing.stream().filter(f -> !f.expectedByLoad()).toList();
    StringBuilder m = new StringBuilder(Long.toString(fp.size()));
    if (!fp.isEmpty()) {
      m.append(": ");
      m.append(
          String.join(
              ", ",
              fp.stream().map(f -> f.metric() + " (" + f.app() + ", " + f.ts() + ")").toList()));
    }
    if (expected > 0) {
      m.append(" (")
          .append(expected)
          .append(" FIRING expected by the load's intended 5% errors or the harness's 2-day test")
          .append(" certificate, not counted)");
    }
    return j.resource(
        "T-SOAK-001",
        "F-S26",
        "alert false positives (template rules under normal load)",
        "0",
        m.toString(),
        fp.isEmpty());
  }

  private static Criterion p99(Judge j, List<Segment> segments) {
    List<Segment> ok =
        segments.stream().filter(s -> s.exitCode() == 0 && !Double.isNaN(s.p99Millis())).toList();
    if (ok.size() < 2) {
      return j.resource(
          "T-SOAK-001", "N-03", "app p99 against the first hours", "<= noise", null, false);
    }
    int k = SoakSchedule.p99BaselinePoints(ok.size());
    List<Double> base = ok.subList(0, k).stream().map(Segment::p99Millis).toList();
    List<Double> rest = ok.subList(k, ok.size()).stream().map(Segment::p99Millis).toList();
    double worsening = SeriesMath.mean(rest) - SeriesMath.mean(base);
    double noise = SeriesMath.noise(base);
    String measured =
        String.format(
            Locale.ROOT,
            "baseline %d points mean %.1f ms (sd %.1f), later %d points mean %.1f ms:"
                + " worsening %+.1f ms, noise %.1f ms",
            base.size(),
            SeriesMath.mean(base),
            SeriesMath.sampleStdDev(base),
            rest.size(),
            SeriesMath.mean(rest),
            worsening,
            noise);
    return j.resource(
        "T-SOAK-001",
        "N-03",
        "app p99 of the later hourly points against the first 6 (mean difference)",
        "<= noise (2 sd of the baseline, at least 5% of its mean)",
        measured,
        worsening <= noise);
  }

  /** Applies the INVALID rule. */
  private record Judge(String invalidReason) {

    Criterion resource(
        String id, String req, String title, String limit, String measured, boolean ok) {
      if (measured == null) {
        return new Criterion(
            id, req, title, limit, "not measured", false, Verdict.INVALID, "not enough points");
      }
      if (invalidReason != null) {
        return new Criterion(
            id,
            req,
            title,
            limit,
            measured,
            false,
            Verdict.INVALID,
            "INVALID run ("
                + invalidReason
                + "); the value is "
                + (ok ? "within" : "over")
                + " the"
                + " limit");
      }
      return new Criterion(
          id, req, title, limit, measured, false, ok ? Verdict.PASS : Verdict.FAIL, "");
    }

    static Criterion invariant(
        String id, String req, String title, String limit, String measured, boolean ok) {
      if (measured == null) {
        return new Criterion(
            id, req, title, limit, "not checked", true, Verdict.FAIL, "the check could not run");
      }
      return new Criterion(
          id, req, title, limit, measured, true, ok ? Verdict.PASS : Verdict.FAIL, "");
    }
  }

  private static List<Point> series(
      List<AgentSample> samples, java.util.function.ToLongFunction<AgentSample> f) {
    List<Point> p = new ArrayList<>();
    for (AgentSample s : samples) {
      long v = f.applyAsLong(s);
      if (v >= 0) {
        p.add(new Point(s.seconds(), v));
      }
    }
    return p;
  }

  private static List<Point> stackSeries(
      List<StackSample> samples, java.util.function.ToLongFunction<StackSample> f) {
    List<Point> p = new ArrayList<>();
    for (StackSample s : samples) {
      long v = f.applyAsLong(s);
      if (v >= 0) {
        p.add(new Point(s.seconds(), v));
      }
    }
    return p;
  }

  private static String skipped(int samples, int used) {
    return used < samples
        ? " (" + (samples - used) + " sample(s) without the class histogram or the buffer estimate)"
        : "";
  }

  /** The parts of the largest agent sample and the app's live heap maximum (reference). */
  private static String heapParts(List<AgentSample> agent) {
    AgentSample top =
        agent.stream()
            .filter(a -> a.agentBytes() >= 0)
            .max(java.util.Comparator.comparingLong(AgentSample::agentBytes))
            .orElse(null);
    if (top == null) {
      return "";
    }
    long live = agent.stream().mapToLong(AgentSample::liveHeapBytes).max().orElse(-1);
    return " (io.argos. classes "
        + mib((double) top.heapBytes())
        + " + agent buffers "
        + mib((double) top.bufferBytes())
        + (live >= 0 ? "; app live heap max " + mib((double) live) + ", reference" : "")
        + ")";
  }

  private static String flatText(List<Point> p) {
    return String.format(
        Locale.ROOT,
        "first %d, min %d, max %d, max deviation %d over %d points",
        Math.round(p.get(0).value()),
        Math.round(p.stream().mapToDouble(Point::value).min().orElse(Double.NaN)),
        Math.round(SeriesMath.max(p)),
        Math.round(SeriesMath.maxDeviation(p)),
        p.size());
  }

  private static String jvmThreadsText(List<Point> jvm) {
    return jvm.isEmpty()
        ? ""
        : String.format(
            Locale.ROOT,
            " (all JVM threads by NMT: %d..%d)",
            Math.round(jvm.stream().mapToDouble(Point::value).min().orElse(Double.NaN)),
            Math.round(SeriesMath.max(jvm)));
  }

  private static String busiest(List<StackSample> stack) {
    return stack.stream()
        .max(java.util.Comparator.comparingLong(StackSample::maxPartsPerPartition))
        .map(s -> s.busiestPartition().isEmpty() ? "" : " (" + s.busiestPartition() + ")")
        .orElse("");
  }

  static String mib(double bytes) {
    return String.format(Locale.ROOT, "%.2f MiB", bytes / MIB);
  }

  static String gb(double bytes) {
    return String.format(Locale.ROOT, "%.3f GB", bytes / 1e9);
  }

  static String signed(double v) {
    return String.format(Locale.ROOT, "%+.2f", v);
  }
}
