/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.BucketQuantile;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.SqlTemplate;
import io.argos.storage.queries.Stats;
import io.argos.storage.queries.Tsv;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Q14 percentile accuracy (A-11, T-ST-042): relative error of tDigest, BFloat16 and DD (relative
 * accuracy 0.01) state merges against quantileExact at 1m, 1h and 1d for p50/p95/p99, the error of
 * the 15-bucket p95 estimate, and the stored tDigest states of app_stat_1m / app_stat_1h when the
 * source is the loaded data. One {@link ProcedureResult} per function ({@code Q14/tdigest}, ...)
 * judged on the maximum error over the groups with at least {@code minCount} values, plus {@code
 * Q14} with the tables (recorded): the error per level, the 1m maximum per day (the maximum of one
 * level depends on how many groups it has: 2,880 minutes a day against 6 days at 1d), and the D-14
 * rebuild determinism (the 1m and 1h states of one day rebuilt twice, single-threaded and with 8
 * threads and small blocks, compared byte for byte and by their finalized values).
 */
public final class PercentileAccuracy {

  /** Candidate functions in the column order of the accuracy steps. */
  public static final List<String> FUNCTIONS = List.of("tdigest", "bfloat16", "dd");

  private static final String[] LEVELS = {"minute", "hour", "day"};
  private static final String[] QUANTILES = {"p50", "p95", "p99"};
  private static final double[] LEVEL_Q = {0.5, 0.95, 0.99};
  private static final Instant SYNTHETIC_START = Instant.parse("2026-01-05T00:00:00Z");

  private PercentileAccuracy() {}

  /** Where the values come from. */
  public enum Source {
    /** xlog of the loaded data (plus the stored states of app_stat_1m / app_stat_1h). */
    DATASET,
    /** q14_sample generated on the server at full-scale per-minute density. */
    SYNTHETIC
  }

  /**
   * Options.
   *
   * @param source value source
   * @param apps synthetic applications
   * @param perMinute synthetic values per application and minute (full scale: 500 TPS over 10
   *     applications = 3,000)
   * @param syntheticDays synthetic days
   * @param minuteDays days compared at 1m (from the last one)
   * @param hourDays days compared at 1h
   * @param dayDays days compared at 1d
   * @param minCount smallest group judged (smaller groups are counted as excluded)
   */
  public record Options(
      Source source,
      int apps,
      int perMinute,
      int syntheticDays,
      int minuteDays,
      int hourDays,
      int dayDays,
      long minCount) {

    /**
     * Bench defaults: synthetic, 2 applications x 3,000 per minute, 3 days, every level judged on
     * all 3 days (1m: 8,640 groups, 1h: 144, 1d: 6).
     */
    public static Options bench() {
      return new Options(Source.SYNTHETIC, 2, 3000, 3, 3, 3, 3, 1000);
    }
  }

  /** Runs Q14 and returns the per-function results followed by the table result. */
  public static List<ProcedureResult> run(BenchContext ctx, Options o) {
    QueryFile f = ctx.file("Q14");
    String src;
    LocalDate last;
    LocalDate first;
    if (o.source() == Source.SYNTHETIC) {
      src = "q14_sample";
      ctx.runner().stepOk(f, "synthetic_drop", Parameters.empty(), Map.of());
      ctx.runner().stepOk(f, "synthetic_create", Parameters.empty(), Map.of());
      long rows = (long) o.apps() * o.perMinute() * 1440L * o.syntheticDays();
      ctx.runner()
          .stepOk(
              f,
              "synthetic_fill",
              Parameters.of(
                  Map.of(
                      "apps", Integer.toString(o.apps()),
                      "per_minute", Integer.toString(o.perMinute()),
                      "start", BenchContext.dateTime64(SYNTHETIC_START),
                      "rows", Long.toString(rows))),
              Map.of());
      first = LocalDate.ofInstant(SYNTHETIC_START, ZoneOffset.UTC);
      last = first.plusDays(o.syntheticDays() - 1L);
    } else {
      src = "xlog";
      last = ctx.date("full_day");
      first = ctx.date("first_day");
    }
    Map<String, List<Double>> errors = new LinkedHashMap<>();
    Map<String, Integer> groups = new LinkedHashMap<>();
    Map<String, Integer> excluded = new LinkedHashMap<>();
    int[] days = {o.minuteDays(), o.hourDays(), o.dayDays()};
    for (int l = 0; l < LEVELS.length; l++) {
      for (LocalDate d : window(first, last, days[l])) {
        Measurement m =
            ctx.runner().stepOk(f, "accuracy_" + LEVELS[l], range(d), Map.of("src", src));
        for (List<String> row : Tsv.rows(m.body())) {
          long n = Long.parseLong(row.get(3));
          if (n < o.minCount()) {
            excluded.merge(LEVELS[l], 1, Integer::sum);
            continue;
          }
          groups.merge(LEVELS[l], 1, Integer::sum);
          double[] exact = array(row.get(4));
          double[] rank2 = array(row.get(9));
          for (int fn = 0; fn < FUNCTIONS.size(); fn++) {
            double[] est = array(row.get(5 + fn));
            for (int q = 0; q < 3; q++) {
              double err = Stats.relativeError(est[q], exact[q]);
              add(errors, FUNCTIONS.get(fn), LEVELS[l], q, err);
              if (l == 0) {
                add(errors, FUNCTIONS.get(fn), "minute@" + d, q, err);
              }
              double midRank = rank2[fn * 3 + q] / (2.0 * n);
              add(
                  errors,
                  "rank:" + FUNCTIONS.get(fn),
                  LEVELS[l],
                  q,
                  Math.abs(midRank - LEVEL_Q[q]));
            }
          }
          long[] hist = longs(row.get(8));
          add(
              errors,
              "bucket",
              LEVELS[l],
              1,
              Stats.relativeError(BucketQuantile.estimate(hist, 0.95), exact[1]));
        }
      }
    }
    List<String> functions = new ArrayList<>(FUNCTIONS);
    if (o.source() == Source.DATASET) {
      functions.add("tdigest_stored");
      String[][] stored = {{"stored_hour", "hour"}, {"stored_day", "day"}};
      int[] storedDays = {o.hourDays(), o.dayDays()};
      for (int s = 0; s < stored.length; s++) {
        for (LocalDate d : window(first, last, storedDays[s])) {
          Measurement m = ctx.runner().stepOk(f, stored[s][0], range(d), Map.of());
          for (List<String> row : Tsv.rows(m.body())) {
            if (Long.parseLong(row.get(3)) < o.minCount()) {
              continue;
            }
            double[] exact = array(row.get(4));
            double[] est = array(row.get(5));
            for (int q = 0; q < 3; q++) {
              add(errors, "tdigest_stored", stored[s][1], q, Stats.relativeError(est[q], exact[q]));
            }
          }
        }
      }
    }

    QueryFile file = ctx.file("Q14");
    List<ProcedureResult> results = new ArrayList<>();
    List<List<String>> rows = new ArrayList<>();
    for (String fn : functions) {
      ProcedureResult r = new ProcedureResult("Q14/" + fn, "Q14 " + fn, file.criterion());
      for (int q = 0; q < 3; q++) {
        double worst = Double.NaN;
        for (String level : LEVELS) {
          List<Double> e = errors.get(key(fn, level, q));
          if (e == null || e.isEmpty()) {
            continue;
          }
          double max = Stats.max(e);
          worst = Double.isNaN(worst) ? max : Math.max(worst, max);
          List<Double> rank = errors.get(key("rank:" + fn, level, q));
          rows.add(
              List.of(
                  fn,
                  level,
                  QUANTILES[q],
                  Integer.toString(e.size()),
                  pct(Stats.mean(e)),
                  pct(Stats.percentile(e, 0.95)),
                  pct(max),
                  rank == null ? "-" : points(Stats.percentile(rank, 0.95)),
                  rank == null ? "-" : points(Stats.max(rank))));
          if (rank != null) {
            r.metric(QUANTILES[q] + "_rank_err_" + level, Stats.max(rank));
          }
        }
        r.metric(QUANTILES[q] + "_err", worst);
      }
      results.add(r.judge());
    }
    List<List<String>> perDay = new ArrayList<>();
    for (LocalDate d : window(first, last, days[0])) {
      for (String fn : FUNCTIONS) {
        List<String> row = new ArrayList<>(List.of(fn, d.toString()));
        for (int q = 0; q < 3; q++) {
          List<Double> e = errors.get(key(fn, "minute@" + d, q));
          if (q == 0) {
            row.add(e == null ? "0" : Integer.toString(e.size()));
          }
          row.add(e == null || e.isEmpty() ? "-" : pct(Stats.max(e)));
          row.add(e == null || e.isEmpty() ? "-" : pct(Stats.percentile(e, 0.95)));
        }
        perDay.add(row);
      }
    }
    for (String level : LEVELS) {
      List<Double> e = errors.get(key("bucket", level, 1));
      if (e != null && !e.isEmpty()) {
        rows.add(
            List.of(
                "bucket (15)",
                level,
                "p95",
                Integer.toString(e.size()),
                pct(Stats.mean(e)),
                pct(Stats.percentile(e, 0.95)),
                pct(Stats.max(e)),
                "-",
                "-"));
      }
    }
    ProcedureResult summary =
        new ProcedureResult("Q14", file.title(), io.argos.storage.queries.Criterion.RECORD);
    summary.table(
        new Table(
            "Relative value error against quantileExact, and rank error |mid-rank(estimate) - q| in"
                + " percentage points (groups with n >= "
                + o.minCount()
                + ")",
            List.of(
                "function",
                "level",
                "quantile",
                "groups",
                "value err mean",
                "value err p95 of groups",
                "value err max",
                "rank err p95 of groups",
                "rank err max"),
            rows));
    summary.note(
        "source: "
            + (o.source() == Source.SYNTHETIC
                ? "q14_sample, "
                    + o.apps()
                    + " app(s) x "
                    + o.perMinute()
                    + " values/min x "
                    + o.syntheticDays()
                    + " day(s)"
                : "xlog of the loaded data " + first + ".." + last)
            + "; groups judged per level "
            + groups
            + ", excluded (n < "
            + o.minCount()
            + ") "
            + excluded);
    summary.table(
        new Table(
            "1m value error per day (maximum and p95 over the minute groups of the day)",
            List.of(
                "function",
                "day",
                "groups",
                "p50 max",
                "p50 p95 of groups",
                "p95 max",
                "p95 p95 of groups",
                "p99 max",
                "p99 p95 of groups"),
            perDay));
    summary.table(stateSizes(ctx, f, src, first));
    summary.table(determinism(ctx, f, src, last));
    if (o.source() == Source.SYNTHETIC) {
      ctx.runner().stepOk(f, "synthetic_drop", Parameters.empty(), Map.of());
    }
    results.add(summary.judge());
    return results;
  }

  private static Table stateSizes(BenchContext ctx, QueryFile f, String src, LocalDate day) {
    Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
    Parameters p =
        Parameters.of(
            Map.of(
                "from", BenchContext.dateTime64(from),
                "to", BenchContext.dateTime64(from.plus(Duration.ofHours(1)))));
    Map<String, String> textual = ctx.runner().textual(Map.of("src", src));
    long states =
        Long.parseLong(
            Tsv.rows(ctx.runner().stepOk(f, "state_groups", p, Map.of("src", src)).body())
                .get(0)
                .get(0));
    List<List<String>> rows = new ArrayList<>();
    for (String fn : FUNCTIONS) {
      String sql = p.inline(SqlTemplate.render(f.step("state_" + fn).sql(), textual));
      byte[] bytes = ctx.client(Account.MIGRATOR).queryBytes(sql);
      rows.add(
          List.of(
              fn,
              Long.toString(states),
              states == 0
                  ? "-"
                  : String.format(Locale.ROOT, "%.0f", bytes.length / (double) states)));
    }
    return new Table(
        "Serialized size of one 1m state (RowBinary, first hour of " + day + ")",
        List.of("function", "states", "bytes per state"),
        rows);
  }

  /**
   * D-14: the 1m states of one day rebuilt twice from the raw values (single-threaded, and with 8
   * threads and small blocks, so partial states merge in another order), the 1h states merged from
   * each, then the two rebuilds compared per function: state bytes and finalized values.
   */
  private static Table determinism(BenchContext ctx, QueryFile f, String src, LocalDate day) {
    Parameters p = range(day);
    String[] runs = {"a", "b"};
    List<List<String>> rows = new ArrayList<>();
    try {
      for (String run : runs) {
        for (String level : List.of("1m", "1h")) {
          String table = "q14_det_" + level + "_" + run;
          ctx.runner().stepOk(f, "determinism_drop", p, Map.of("copy", table));
          ctx.runner().stepOk(f, "determinism_create", p, Map.of("copy", table));
        }
        ctx.runner()
            .stepOk(
                f,
                "determinism_fill_1m_" + run,
                p,
                Map.of("copy", "q14_det_1m_" + run, "src", src));
        ctx.runner()
            .stepOk(
                f,
                "determinism_fill_1h_" + run,
                p,
                Map.of("copy", "q14_det_1h_" + run, "src", "q14_det_1m_" + run));
      }
      for (String level : List.of("1m", "1h")) {
        List<String> a =
            Tsv.rows(
                    ctx.runner()
                        .stepOk(f, "determinism_hash", p, Map.of("copy", "q14_det_" + level + "_a"))
                        .body())
                .get(0);
        List<String> b =
            Tsv.rows(
                    ctx.runner()
                        .stepOk(f, "determinism_hash", p, Map.of("copy", "q14_det_" + level + "_b"))
                        .body())
                .get(0);
        for (int fn = 0; fn < FUNCTIONS.size(); fn++) {
          rows.add(
              List.of(
                  FUNCTIONS.get(fn),
                  level,
                  a.get(0),
                  a.get(1 + fn).equals(b.get(1 + fn)) ? "identical" : "MISMATCH",
                  a.get(4 + fn).equals(b.get(4 + fn)) ? "identical" : "MISMATCH"));
        }
      }
    } finally {
      for (String run : runs) {
        for (String level : List.of("1m", "1h")) {
          ctx.runner()
              .step(f, "determinism_drop", p, Map.of("copy", "q14_det_" + level + "_" + run));
        }
      }
    }
    return new Table(
        "D-14 rebuild determinism on "
            + day
            + ": rebuild a (max_threads 1) vs rebuild b (max_threads 8, max_block_size 4096)",
        List.of("function", "level", "states", "state bytes", "finalized p50/p95/p99"),
        rows);
  }

  private static List<LocalDate> window(LocalDate first, LocalDate last, int days) {
    List<LocalDate> out = new ArrayList<>();
    for (int i = days - 1; i >= 0; i--) {
      LocalDate d = last.minusDays(i);
      if (!d.isBefore(first)) {
        out.add(d);
      }
    }
    return out;
  }

  private static Parameters range(LocalDate d) {
    Instant from = d.atStartOfDay(ZoneOffset.UTC).toInstant();
    return Parameters.of(
        Map.of(
            "from", BenchContext.dateTime64(from),
            "to", BenchContext.dateTime64(from.plus(Duration.ofDays(1)))));
  }

  private static void add(
      Map<String, List<Double>> errors, String fn, String level, int q, double e) {
    errors.computeIfAbsent(key(fn, level, q), k -> new ArrayList<>()).add(e);
  }

  private static String key(String fn, String level, int q) {
    return fn + "|" + level + "|" + q;
  }

  static double[] array(String text) {
    String t = text.strip();
    if (!t.startsWith("[") || !t.endsWith("]")) {
      throw new IllegalArgumentException("Not an array: " + text);
    }
    String inner = t.substring(1, t.length() - 1);
    if (inner.isEmpty()) {
      return new double[0];
    }
    String[] parts = inner.split(",", -1);
    double[] out = new double[parts.length];
    for (int i = 0; i < parts.length; i++) {
      out[i] = Double.parseDouble(parts[i]);
    }
    return out;
  }

  static long[] longs(String text) {
    double[] d = array(text);
    long[] out = new long[d.length];
    for (int i = 0; i < d.length; i++) {
      out[i] = (long) d[i];
    }
    return out;
  }

  static String points(double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.3f pp", v * 100);
  }

  static String pct(double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.3f %%", v * 100);
  }
}
