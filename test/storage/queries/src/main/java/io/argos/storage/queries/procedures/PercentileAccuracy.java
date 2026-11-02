/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.BucketQuantile;
import io.argos.storage.queries.Criterion;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.SqlTemplate;
import io.argos.storage.queries.Stats;
import io.argos.storage.queries.Tsv;
import io.argos.storage.schema.RollupRebuild;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
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
 * the 15-bucket p95 estimate, and the stored quantilesDD states of app_stat_1m / app_stat_1h
 * (ADR-0007 C-53) when the source is the loaded data. One {@link ProcedureResult} per function
 * ({@code Q14/tdigest}, ...), judged on the criteria of C-53: the p95 over the 1m groups of the
 * value error of p50 and p95 at most 2 % ({@code p50_err_1m_p95}, {@code p95_err_1m_p95}), the
 * maximum value error of p99 at 1h and above at most 5 % ({@code p99_err_1h_max}) and the maximum
 * rank error of the 1m p99 at most 0.5 percentage points ({@code p99_rank_err_minute}); the stored
 * states ({@code Q14/dd_stored}, 1h and 1d only) on {@code p99_err_1h_max}. Groups with fewer than
 * {@code minCount} values are not judged. {@code Q14} holds the tables (recorded): the error per
 * level, the 1m maximum per day, and the D-14 rebuild determinism (the 1m and 1h states of one day
 * rebuilt twice, single-threaded and with 8 threads and small blocks, compared byte for byte and by
 * their finalized values). On the loaded data {@code Q14/d14_rebuild} also rebuilds the rollup
 * tables of the last full day with the procedure of C-59 ({@link RollupRebuild}: staging table,
 * REPLACE PARTITION, 1m day then 1h month) twice and compares the stored DD states of the
 * transaction rollups byte for byte: view-filled against rebuild 1, rebuild 1 against rebuild 2.
 */
public final class PercentileAccuracy {

  /** Candidate functions in the column order of the accuracy steps. */
  public static final List<String> FUNCTIONS = List.of("tdigest", "bfloat16", "dd");

  private static final String[] LEVELS = {"minute", "hour", "day"};
  private static final String[] QUANTILES = {"p50", "p95", "p99"};
  private static final double[] LEVEL_Q = {0.5, 0.95, 0.99};
  private static final Instant SYNTHETIC_START = Instant.parse("2026-01-05T00:00:00Z");

  /** Stored states of the loaded data (production path: app_stat_1m to 1h to 1d). */
  public static final String STORED = "dd_stored";

  /** C-53 criterion of the stored states (they exist at 1h and 1d only). */
  private static final Criterion STORED_CRITERION = Criterion.parse("p99_err_1h_max <= 0.05");

  /** Key columns of the transaction rollups whose q_elapsed DD states the C-59 check compares. */
  private static final Map<String, String> TRANSACTION_ROLLUP_KEYS = new LinkedHashMap<>();

  static {
    TRANSACTION_ROLLUP_KEYS.put("app_stat_1m", "app_id, env, kind, minute");
    TRANSACTION_ROLLUP_KEYS.put(
        "service_stat_1m", "app_id, env, kind, service_id, app_version_id, minute");
    TRANSACTION_ROLLUP_KEYS.put("instance_stat_1m", "app_id, env, instance_id, kind, minute");
    TRANSACTION_ROLLUP_KEYS.put("app_stat_1h", "app_id, env, kind, hour");
    TRANSACTION_ROLLUP_KEYS.put(
        "service_stat_1h", "app_id, env, kind, service_id, app_version_id, hour");
  }

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
      functions.add(STORED);
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
              add(errors, STORED, stored[s][1], q, Stats.relativeError(est[q], exact[q]));
            }
          }
        }
      }
    }

    QueryFile file = ctx.file("Q14");
    List<ProcedureResult> results = new ArrayList<>();
    List<List<String>> rows = new ArrayList<>();
    for (String fn : functions) {
      ProcedureResult r =
          new ProcedureResult(
              "Q14/" + fn, "Q14 " + fn, fn.equals(STORED) ? STORED_CRITERION : file.criterion());
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
        List<Double> minute = errors.get(key(fn, "minute", q));
        if (q < 2 && minute != null && !minute.isEmpty()) {
          r.metric(QUANTILES[q] + "_err_1m_p95", Stats.percentile(minute, 0.95));
        }
        if (q == 2) {
          List<Double> coarse = new ArrayList<>();
          for (String level : List.of("hour", "day")) {
            coarse.addAll(errors.getOrDefault(key(fn, level, q), List.of()));
          }
          if (!coarse.isEmpty()) {
            r.metric("p99_err_1h_max", Stats.max(coarse));
          }
        }
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
    if (o.source() == Source.DATASET) {
      results.add(rollupRebuild(ctx, last));
    }
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

  /**
   * D-14 on the stored rollups (ADR-0007 C-53, C-59): the rollup tables of {@code day} are rebuilt
   * twice with the C-59 procedure (every 1m table for the day, then every 1h table for its month,
   * staging table + REPLACE PARTITION; rebuild 1 with max_threads 1, rebuild 2 with max_threads 8
   * and max_block_size 4096), and the q_elapsed quantilesDD states of the transaction rollups are
   * compared per key: the hex of the state merged per key and the finalized p50/p95/p99, as
   * order-independent sums of hashes. Metrics: {@code rebuild_state_mismatches} (rebuild 1 vs 2)
   * and {@code view_state_mismatches} (the states the views wrote vs rebuild 1), tables with a
   * difference; the criterion is byte identity for both.
   */
  static ProcedureResult rollupRebuild(BenchContext ctx, LocalDate day) {
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    SchemaParameters schema = ctx.schema();
    Map<String, List<String>> view = rollupStateHashes(ctx, schema, day, "view");
    List<Map<String, String>> runs =
        List.of(
            Map.of("max_threads", "1", "max_insert_threads", "1"),
            Map.of("max_threads", "8", "max_block_size", "4096", "max_insert_threads", "1"));
    List<Map<String, List<String>>> rebuilt = new ArrayList<>();
    for (int run = 0; run < runs.size(); run++) {
      List<String> statements = RollupRebuild.dayStatements(renderer, schema, day);
      for (int i = 0; i < statements.size(); i++) {
        String sql = statements.get(i);
        ctx.runner()
            .statement(
                Account.MIGRATOR,
                "Q14/c59_rebuild_" + (run + 1) + "_" + i,
                sql,
                sql.startsWith("INSERT INTO ") ? runs.get(run) : Map.of());
      }
      rebuilt.add(rollupStateHashes(ctx, schema, day, "rebuild" + (run + 1)));
    }
    List<List<String>> rows = new ArrayList<>();
    int rebuildMismatches = 0;
    int viewMismatches = 0;
    for (String table : TRANSACTION_ROLLUP_KEYS.keySet()) {
      List<String> v = view.get(table);
      List<String> a = rebuilt.get(0).get(table);
      List<String> b = rebuilt.get(1).get(table);
      boolean rebuildBytes = a.get(1).equals(b.get(1)) && a.get(0).equals(b.get(0));
      boolean viewBytes = v.get(1).equals(a.get(1)) && v.get(0).equals(a.get(0));
      rebuildMismatches += rebuildBytes ? 0 : 1;
      viewMismatches += viewBytes ? 0 : 1;
      rows.add(
          List.of(
              table,
              v.get(0) + " / " + a.get(0) + " / " + b.get(0),
              rebuildBytes ? "identical" : "MISMATCH",
              a.get(2).equals(b.get(2)) ? "identical" : "MISMATCH",
              viewBytes ? "identical" : "MISMATCH",
              v.get(2).equals(a.get(2)) ? "identical" : "MISMATCH"));
    }
    return new ProcedureResult(
            "Q14/d14_rebuild",
            "Q14 D-14: stored DD states after the C-59 rebuild",
            Criterion.parse("rebuild_state_mismatches == 0, view_state_mismatches == 0"))
        .metric("rebuild_state_mismatches", rebuildMismatches)
        .metric("view_state_mismatches", viewMismatches)
        .table(
            new Table(
                "D-14 rollup rebuild of "
                    + day
                    + " (C-59: staging + REPLACE PARTITION, 1m day then 1h month "
                    + RollupRebuild.monthPartition(day)
                    + "): q_elapsed quantilesDD states per key",
                List.of(
                    "table",
                    "keys view / rebuild 1 / rebuild 2",
                    "state bytes rebuild 1 vs 2",
                    "finalized rebuild 1 vs 2",
                    "state bytes view vs rebuild 1",
                    "finalized view vs rebuild 1"),
                rows))
        .judge();
  }

  /** Per transaction rollup: keys, hash of the merged state bytes, hash of the finalized values. */
  private static Map<String, List<String>> rollupStateHashes(
      BenchContext ctx, SchemaParameters schema, LocalDate day, String label) {
    Map<String, List<String>> hashes = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : TRANSACTION_ROLLUP_KEYS.entrySet()) {
      String table = e.getKey();
      String keys = e.getValue();
      String where =
          RollupRebuild.hourly(table)
              ? "toYYYYMM(hour) = " + RollupRebuild.monthPartition(day)
              : "toDate(minute) = " + RollupRebuild.dayPartition(day);
      String sql =
          "SELECT count(), sum(cityHash64("
              + keys
              + ", s)), sum(cityHash64("
              + keys
              + ", v)) FROM (SELECT "
              + keys
              + ", hex(quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed)) AS s,"
              + " toString(quantilesDDMerge(0.01, 0.5, 0.95, 0.99)(q_elapsed)) AS v FROM "
              + schema.table(table)
              + " WHERE "
              + where
              + " GROUP BY "
              + keys
              + ")";
      Measurement m =
          ctx.runner()
              .statement(Account.MIGRATOR, "Q14/c59_hash_" + label + "_" + table, sql, Map.of());
      hashes.put(table, Tsv.rows(m.body()).get(0));
    }
    return hashes;
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
