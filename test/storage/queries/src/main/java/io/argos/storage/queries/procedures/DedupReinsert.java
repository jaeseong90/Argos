/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseException;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.Tsv;
import io.argos.storage.queries.dataset.CiDataset;
import io.argos.storage.queries.dataset.CiDataset.Block;
import io.argos.storage.queries.dataset.TxModel.Tx;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q15 duplicate re-insert (13 §5, D-13, T-ST-032) on the loaded data, through the writer path: one
 * block per raw table inserted once, then 100 more times with the same token; one view broken (its
 * target refuses rows), a new xlog block fails, the view is repaired and the same block is re-sent
 * with the same token; then 100 more times. Metrics: {@code increase} (sum of the absolute
 * deviations of every raw and rollup total from the expected value after each re-insert phase),
 * {@code broken_view_filled} (1 when the repaired view and its 1h cascade received the block on the
 * retry), {@code window_mismatches} (Q12 rows over the window afterwards).
 */
public final class DedupReinsert {

  private static final List<String> TABLES =
      List.of(
          "xlog",
          "profile_step",
          "metric",
          "sql_stat_raw",
          "ext_stat_raw",
          "app_stat_1m",
          "app_stat_1h",
          "service_stat_1m",
          "service_stat_1h",
          "instance_stat_1m",
          "error_stat_1m",
          "sql_stat_1m",
          "sql_stat_1h",
          "ext_stat_1m",
          "ext_stat_1h",
          "metric_1m",
          "metric_1h");

  private DedupReinsert() {}

  /**
   * Runs Q15.
   *
   * @param ctx context
   * @param transactions transactions of the xlog block (2,000 in CI)
   * @param repeats re-inserts per phase (13 §15: 100)
   */
  public static ProcedureResult run(BenchContext ctx, int transactions, int repeats) {
    QueryFile f = ctx.file("Q15");
    ProcedureResult result = new ProcedureResult("Q15", f.title(), f.criterion());
    Instant end = ctx.instant("end_minute");
    Instant from = end.minus(Duration.ofHours(3));
    Instant to = from.plus(Duration.ofMinutes(10));
    Parameters window =
        Parameters.of(
            Map.of(
                "window_from", BenchContext.dateTime(from),
                "window_to", BenchContext.dateTime(to)));
    ClickHouseHttp writer = ctx.client(Account.WRITER);
    CiDataset.Extra first = CiDataset.extra(ctx.schema(), seed(ctx, 1501), from, to, transactions);
    CiDataset.Extra second = CiDataset.extra(ctx.schema(), seed(ctx, 1502), from, to, transactions);

    Map<String, Double> s0 = snapshot(ctx, f, window);
    first.blocks().values().forEach(b -> CiDataset.insert(writer, b));
    Map<String, Double> s1 = snapshot(ctx, f, window);
    long started = System.nanoTime();
    for (int i = 0; i < repeats; i++) {
      first.blocks().values().forEach(b -> CiDataset.insert(writer, b));
    }
    double perInsertMs =
        (System.nanoTime() - started) / 1e6 / Math.max(1, repeats * first.blocks().size());
    Map<String, Double> s2 = snapshot(ctx, f, window);
    double increase = deviation(s2, s1);

    ctx.runner().stepOk(f, "break_view", Parameters.empty(), Map.of());
    Block b2 = second.blocks().get("xlog");
    String failure = "";
    try {
      CiDataset.insert(writer, b2);
    } catch (ClickHouseException e) {
      failure = e.errorName();
    } finally {
      // Keep the data usable even when an assertion below fails.
      ctx.runner().stepOk(f, "repair_view", Parameters.empty(), Map.of());
    }
    Map<String, Double> s3 = snapshot(ctx, f, window);
    // The view is repaired before the retry, as the writer's retry would find it after an operator
    // fixed the cause; S3 is the state left by the failed insert.
    CiDataset.insert(writer, b2);
    Map<String, Double> s4 = snapshot(ctx, f, window);
    Map<String, Double> expected4 = new LinkedHashMap<>(s2);
    contribution(second.transactions()).forEach((t, v) -> expected4.merge(t, v, Double::sum));
    increase += deviation(s4, expected4);
    for (int i = 0; i < repeats; i++) {
      CiDataset.insert(writer, b2);
    }
    Map<String, Double> s5 = snapshot(ctx, f, window);
    increase += deviation(s5, s4);
    // The statistics and steps of the second block's transactions, so that the D-12 totals (Q13)
    // stay equal after this procedure (outside the measured phases).
    for (String companion : List.of("sql_stat_raw", "ext_stat_raw", "profile_step")) {
      CiDataset.insert(writer, second.blocks().get(companion));
    }

    long n2 = b2.rows();
    boolean filled =
        s3.get("service_stat_1m").equals(s2.get("service_stat_1m"))
            && s4.get("service_stat_1m") - s3.get("service_stat_1m") == n2
            && s4.get("service_stat_1h") - s2.get("service_stat_1h") == n2
            && s3.get("xlog") - s2.get("xlog") == n2;
    long mismatches =
        Tsv.count(ctx.runner().stepOk(ctx.file("Q12"), "window_minute", window, Map.of()).body());

    List<List<String>> rows = new ArrayList<>();
    for (String t : TABLES) {
      rows.add(
          List.of(
              t,
              num(s0.get(t)),
              num(s1.get(t) - s0.get(t)),
              num(s2.get(t) - s1.get(t)),
              num(s3.get(t) - s2.get(t)),
              num(s4.get(t) - s3.get(t)),
              num(s5.get(t) - s4.get(t))));
    }
    return result
        .metric("increase", increase)
        .metric("broken_view_filled", filled ? 1 : 0)
        .metric("window_mismatches", mismatches)
        .metric("failed_insert_error", "VIOLATED_CONSTRAINT".equals(failure) ? 1 : 0)
        .metric("reinsert_round_trip_ms", perInsertMs)
        .table(
            new Table(
                "Totals in the window "
                    + BenchContext.dateTime(from)
                    + " .. "
                    + BenchContext.dateTime(to)
                    + " (raw: rows, rollups: sum(count) / sum(sample_count))",
                List.of(
                    "table",
                    "before",
                    "+ first insert",
                    "+ " + repeats + " re-inserts",
                    "+ failed insert (view broken)",
                    "+ retry after repair",
                    "+ " + repeats + " re-inserts"),
                rows))
        .note(
            "blocks: "
                + first.blocks().size()
                + " raw tables x "
                + transactions
                + " transactions; failed insert error "
                + (failure.isEmpty() ? "<none>" : failure)
                + "; mean duplicate insert round trip "
                + String.format(java.util.Locale.ROOT, "%.1f", perInsertMs)
                + " ms")
        .judge();
  }

  /**
   * A seed unique to this run: blocks of an earlier run on the same data carry other tokens, so a
   * repeated run starts from blocks the server has not seen.
   */
  static long seed(BenchContext ctx, long salt) {
    return ctx.runner().options().runId().hashCode() * 1_000_003L + salt;
  }

  /** Expected total increase per table when the xlog block of {@code txs} lands exactly once. */
  static Map<String, Double> contribution(List<Tx> txs) {
    double n = txs.size();
    double errors = txs.stream().filter(t -> t.error() && t.errorGroupId() != 0).count();
    Map<String, Double> c = new LinkedHashMap<>();
    c.put("xlog", n);
    c.put("app_stat_1m", n);
    c.put("app_stat_1h", n);
    c.put("service_stat_1m", n);
    c.put("service_stat_1h", n);
    c.put("instance_stat_1m", n);
    c.put("error_stat_1m", errors);
    return c;
  }

  static Map<String, Double> snapshot(BenchContext ctx, QueryFile f, Parameters window) {
    Map<String, Double> totals = new LinkedHashMap<>();
    for (List<String> row : Tsv.rows(ctx.runner().stepOk(f, "snapshot", window, Map.of()).body())) {
      totals.put(row.get(0), row.get(1).equals("nan") ? 0 : Double.parseDouble(row.get(1)));
    }
    for (String t : TABLES) {
      totals.putIfAbsent(t, 0.0);
    }
    return totals;
  }

  private static double deviation(Map<String, Double> actual, Map<String, Double> expected) {
    double sum = 0;
    for (String t : TABLES) {
      sum += Math.abs(actual.getOrDefault(t, 0.0) - expected.getOrDefault(t, 0.0));
    }
    return sum;
  }

  private static String num(Double v) {
    return v == null ? "-" : (v == Math.rint(v) ? Long.toString(v.longValue()) : v.toString());
  }
}
