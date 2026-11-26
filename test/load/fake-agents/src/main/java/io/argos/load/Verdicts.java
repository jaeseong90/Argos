/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.load.Criterion.Kind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The verdict rules of the load report (T-NFR-06, T-NFR-18, D-13; 01 N-06, N-18; 12 §11): which
 * criterion is judged how, from the {@link Measurements} of a run.
 *
 * <ul>
 *   <li>Timing and resource criteria ({@link Kind#MEASUREMENT}) are PASS or FAIL only when the run
 *       is N-06 at full scale ({@link LoadOptions#fullScale()}); any other run reports them INVALID
 *       with the measured value as a reference. A missing measurement of a full-scale run is FAIL.
 *   <li>Invariants ({@link Kind#INVARIANT}: D-13 counts, OOM 0, the ClickHouse settings) are PASS
 *       or FAIL in every profile, and a FAIL fails the task.
 * </ul>
 */
public final class Verdicts {

  /** N-06 transactions per second. */
  public static final double N06_TPS = 5_000;

  /** N-06 profile step rows per second. */
  public static final double N06_STEP_ROWS = 50_000;

  /** N-06 statistics rows per second. */
  public static final double N06_STAT_ROWS = 10_000;

  /** N-06 duration. */
  public static final Duration N06_DURATION = Duration.ofHours(1);

  /** N-06 ingest lag p99. */
  public static final double N06_LAG_P99_SECONDS = 5;

  /** N-06 dashboard poll p95. */
  public static final double N06_DASHBOARD_P95_MILLIS = 2_000;

  /** N-06 active parts per partition (strictly below). */
  public static final long N06_PARTS_PER_PARTITION = 300;

  /** A sustained rate counts as reached at this share of the target (scheduler jitter). */
  public static final double RATE_TOLERANCE = 0.98;

  private Verdicts() {}

  /**
   * The facts the rules judge. Negative counts and NaN values mean "not measured".
   *
   * @param fullScale N-06 at full scale ({@link LoadOptions#fullScale()})
   * @param profile the profile id (for the INVALID note)
   * @param window the measured window
   * @param txPerSecond accepted transactions per second in the window
   * @param stepRowsPerSecond accepted profile step rows per second in the window
   * @param statRowsPerSecond accepted statistics rows per second in the window
   * @param lagP99Seconds {@code argos_ingest_lag_seconds} p99 of the window
   * @param userCalls dashboard calls in the window
   * @param userErrors dashboard calls that did not answer 200
   * @param userP95Millis dashboard p95 of the window
   * @param users concurrent dashboard users
   * @param maxActiveParts the largest active parts count of one partition during the run
   * @param delayedInserts {@code DelayedInserts} events during the run
   * @param settingsViolations violations of {@link ClickHouseSettingsRule}; null when not read
   * @param oomFindings OOM findings (server, ClickHouse, containers); null when not checked
   * @param sent transactions the server acknowledged (generator counter, the D-13 reference)
   * @param xlogRows {@code count()} of {@code xlog} for the load apps
   * @param xlogDistinct {@code uniqExact(tx_id)} of {@code xlog} for the load apps
   * @param serviceStatCount {@code sum(count)} of {@code service_stat_1m} for the load apps
   * @param serverRssPeak the server's resident set peak (bytes)
   * @param serverBudget heap + 1 GiB (12 §11, 17 §7.1)
   * @param clickHouseMemoryPeak ClickHouse {@code MemoryTracking} peak (bytes)
   * @param clickHouseServerLimit {@code max_server_memory_usage} (bytes)
   */
  public record Measurements(
      boolean fullScale,
      String profile,
      Duration window,
      double txPerSecond,
      double stepRowsPerSecond,
      double statRowsPerSecond,
      double lagP99Seconds,
      long userCalls,
      long userErrors,
      double userP95Millis,
      int users,
      long maxActiveParts,
      long delayedInserts,
      List<String> settingsViolations,
      List<String> oomFindings,
      long sent,
      long xlogRows,
      long xlogDistinct,
      long serviceStatCount,
      long serverRssPeak,
      long serverBudget,
      long clickHouseMemoryPeak,
      long clickHouseServerLimit) {

    /** Copies the lists. */
    public Measurements {
      settingsViolations = settingsViolations == null ? null : List.copyOf(settingsViolations);
      oomFindings = oomFindings == null ? null : List.copyOf(oomFindings);
    }
  }

  /** Every criterion of the N-06 / N-18 / D-13 table, in report order. */
  public static List<Criterion> evaluate(Measurements m) {
    List<Criterion> c = new ArrayList<>();
    c.add(
        rate(
            m,
            "Transactions per second, sustained",
            N06_TPS,
            m.txPerSecond(),
            "accepted by the" + " server (ack minus dropped_items) in the measured window"));
    c.add(
        rate(
            m,
            "Profile step rows per second, sustained",
            N06_STEP_ROWS,
            m.stepRowsPerSecond(),
            "steps of acknowledged profiles in the measured window"));
    c.add(
        rate(
            m,
            "Statistics rows per second (SqlStat + ExtStat), sustained",
            N06_STAT_ROWS,
            m.statRowsPerSecond(),
            "acknowledged rows of the 10 s windows in the measured window"));
    c.add(
        measurement(
            m,
            "T-NFR-06",
            "N-06",
            "Duration of the measured window",
            ">= 1 h",
            m.window() == null ? "n/a" : Format.duration(m.window()),
            m.window() != null,
            m.window() != null && m.window().compareTo(N06_DURATION) >= 0,
            "after the warm-up"));
    c.add(
        measurement(
            m,
            "T-NFR-06",
            "N-06",
            "Ingest lag p99 (argos_ingest_lag_seconds)",
            "<= 5 s",
            Format.seconds(m.lagP99Seconds()),
            !Double.isNaN(m.lagP99Seconds()),
            m.lagP99Seconds() <= N06_LAG_P99_SECONDS,
            "receive to ClickHouse insert acknowledged; histogram difference over the window"));
    c.add(
        measurement(
            m,
            "T-NFR-06",
            "N-06",
            "Dashboard " + m.users() + " users, 5 s polling: p95",
            "<= 2 s with 10 users, no error",
            Format.millis(m.userP95Millis())
                + " ("
                + Format.count(m.userCalls())
                + " calls, "
                + Format.count(m.userErrors())
                + " errors)",
            m.userCalls() > 0 && !Double.isNaN(m.userP95Millis()),
            m.userP95Millis() <= N06_DASHBOARD_P95_MILLIS && m.userErrors() == 0 && m.users() >= 10,
            "client wall time of dashboard 1 h, XLog 5 min and SQL Top N 1 h"));
    c.add(
        measurement(
            m,
            "T-NFR-06",
            "N-06",
            "Active parts per partition (maximum during the run)",
            "< 300",
            m.maxActiveParts() < 0 ? "n/a" : Long.toString(m.maxActiveParts()),
            m.maxActiveParts() >= 0,
            m.maxActiveParts() < N06_PARTS_PER_PARTITION,
            "system.parts every 5 s"));
    c.add(
        measurement(
            m,
            "T-NFR-06",
            "N-06",
            "DelayedInserts during the run",
            "0",
            m.delayedInserts() < 0 ? "n/a" : Long.toString(m.delayedInserts()),
            m.delayedInserts() >= 0,
            m.delayedInserts() == 0,
            "system.events difference from the first to the last sample"));
    c.add(
        invariant(
            "T-NFR-06",
            "D-13",
            "Sent = count(xlog) = uniqExact(tx_id) = sum(service_stat_1m.count)",
            "equal (late arrivals, duplicates and clock skew included)",
            m.sent() < 0
                ? "n/a"
                : String.format(
                    Locale.ROOT,
                    "%,d / %,d / %,d / %,d",
                    m.sent(),
                    m.xlogRows(),
                    m.xlogDistinct(),
                    m.serviceStatCount()),
            m.sent() >= 0 && m.xlogRows() >= 0,
            m.sent() == m.xlogRows()
                && m.sent() == m.xlogDistinct()
                && m.sent() == m.serviceStatCount(),
            "generator counter of acknowledged transactions against the load apps' rows"));
    c.add(
        invariant(
            "T-NFR-18",
            "N-18",
            "ClickHouse memory settings",
            "max_server_memory_usage per MemoryBudget, mark_cache_size 512 MiB,"
                + " uncompressed_cache_size 0, reader query 3 GiB share / 10 s / 4 threads",
            m.settingsViolations() == null
                ? "n/a"
                : m.settingsViolations().isEmpty()
                    ? "as configured"
                    : String.join("; ", m.settingsViolations()),
            m.settingsViolations() != null,
            m.settingsViolations() != null && m.settingsViolations().isEmpty(),
            "merged server configuration and the reader's getSetting()"));
    c.add(
        invariant(
            "T-NFR-18",
            "N-18",
            "OOM",
            "0 (server, ClickHouse, containers)",
            m.oomFindings() == null
                ? "n/a"
                : m.oomFindings().isEmpty() ? "0" : String.join("; ", m.oomFindings()),
            m.oomFindings() != null,
            m.oomFindings() != null && m.oomFindings().isEmpty(),
            "server exit, hs_err, OutOfMemoryError in the log; ClickHouse MEMORY_LIMIT_EXCEEDED;"
                + " docker OOMKilled. The 72 h part is WO-52"));
    c.add(
        measurement(
            m,
            "T-NFR-06",
            "12 §11",
            "Server resident set peak",
            "<= heap + 1 GiB (" + Format.bytes(m.serverBudget()) + ")",
            Format.bytes(m.serverRssPeak()),
            m.serverRssPeak() >= 0,
            m.serverRssPeak() <= m.serverBudget(),
            "see the memory section for the source"));
    c.add(
        measurement(
            m,
            "T-NFR-18",
            "N-18",
            "ClickHouse MemoryTracking peak",
            "<= max_server_memory_usage (" + Format.bytes(m.clickHouseServerLimit()) + ")",
            Format.bytes(m.clickHouseMemoryPeak()),
            m.clickHouseMemoryPeak() >= 0 && m.clickHouseServerLimit() > 0,
            m.clickHouseMemoryPeak() <= m.clickHouseServerLimit(),
            "system.metrics every 5 s"));
    return c;
  }

  /** Whether any row fails the task (an invariant that did not pass). */
  public static boolean taskFails(List<Criterion> criteria) {
    return criteria.stream().anyMatch(Criterion::failsTask);
  }

  /** The verdict of a measurement criterion. */
  public static Verdict measurementVerdict(boolean fullScale, boolean measured, boolean pass) {
    if (!fullScale) {
      return Verdict.INVALID;
    }
    return measured && pass ? Verdict.PASS : Verdict.FAIL;
  }

  /** The verdict of an invariant. */
  public static Verdict invariantVerdict(boolean measured, boolean pass) {
    return measured && pass ? Verdict.PASS : Verdict.FAIL;
  }

  private static Criterion rate(
      Measurements m, String title, double target, double measured, String how) {
    return measurement(
        m,
        "T-NFR-06",
        "N-06",
        title,
        String.format(Locale.ROOT, ">= %,.0f/s", target),
        Double.isNaN(measured) ? "n/a" : Format.rate(measured),
        !Double.isNaN(measured),
        measured >= target * RATE_TOLERANCE,
        how);
  }

  private static Criterion measurement(
      Measurements m,
      String id,
      String requirement,
      String title,
      String target,
      String measured,
      boolean wasMeasured,
      boolean pass,
      String how) {
    Verdict v = measurementVerdict(m.fullScale(), wasMeasured, pass);
    String note =
        v == Verdict.INVALID
            ? "profile "
                + m.profile()
                + ", not N-06 at full scale on 13 §7.1 hardware: reference"
                + " only. "
                + how
            : how;
    return new Criterion(id, requirement, title, target, measured, Kind.MEASUREMENT, v, note);
  }

  private static Criterion invariant(
      String id,
      String requirement,
      String title,
      String target,
      String measured,
      boolean wasMeasured,
      boolean pass,
      String how) {
    return new Criterion(
        id,
        requirement,
        title,
        target,
        measured,
        Kind.INVARIANT,
        invariantVerdict(wasMeasured, pass),
        how);
  }
}
