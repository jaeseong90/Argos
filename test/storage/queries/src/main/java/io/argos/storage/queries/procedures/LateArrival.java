/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.Tsv;
import io.argos.storage.queries.VariantResult;
import io.argos.storage.queries.dataset.CiDataset;
import io.argos.storage.queries.dataset.TxModel.Tx;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Q16 late arrival (D-03, T-ST-033): {@code rows} xlog rows whose ts lie one hour before the end of
 * the data, inserted through the writer path long after their minutes were rolled up. Every 1m
 * rollup of those minutes and their hours must grow by exactly the inserted rows ({@code
 * delta_mismatches}), and Q12 must find no mismatch afterwards, in the window and over the full
 * range ({@code rows}).
 */
public final class LateArrival {

  private LateArrival() {}

  /**
   * Runs Q16.
   *
   * @param ctx context
   * @param rows late rows (13 §15: 20,000)
   * @param blockRows rows per insert block
   */
  public static ProcedureResult run(BenchContext ctx, int rows, int blockRows) {
    QueryFile f = ctx.file("Q16");
    Instant end = ctx.instant("end_minute");
    Instant from = end.minus(Duration.ofMinutes(61));
    Instant to = from.plus(Duration.ofMinutes(10));
    Parameters window =
        Parameters.of(
            Map.of(
                "window_from", BenchContext.dateTime(from),
                "window_to", BenchContext.dateTime(to)));
    Map<String, long[]> minutesBefore = minutes(ctx, f, window);
    Map<String, long[]> hoursBefore = hours(ctx, f, window);

    CiDataset.Extra late =
        CiDataset.extra(ctx.schema(), DedupReinsert.seed(ctx, 1601), from, to, rows);
    List<Tx> txs = late.transactions();
    int blocks = 0;
    for (int i = 0; i < txs.size(); i += blockRows) {
      List<Tx> part = txs.subList(i, Math.min(txs.size(), i + blockRows));
      CiDataset.insert(
          ctx.client(Account.WRITER),
          CiDataset.xlogBlock(ctx.schema(), part, DedupReinsert.seed(ctx, 1601L * 1000 + blocks)));
      blocks++;
    }
    // The statistics and steps of the late transactions arrive with them (one agent batch), so the
    // D-12 totals (Q13) stay equal after this procedure.
    for (String companion : List.of("sql_stat_raw", "ext_stat_raw", "profile_step")) {
      CiDataset.insert(ctx.client(Account.WRITER), late.blocks().get(companion));
    }
    Map<String, long[]> minutesAfter = minutes(ctx, f, window);
    Map<String, long[]> hoursAfter = hours(ctx, f, window);

    Map<String, Long> inserted = new TreeMap<>();
    Map<String, Long> insertedHours = new TreeMap<>();
    for (Tx tx : txs) {
      Instant minute = Instant.ofEpochMilli(tx.tsMs() / 60_000 * 60_000);
      Instant hour = Instant.ofEpochMilli(tx.tsMs() / 3_600_000 * 3_600_000);
      inserted.merge(BenchContext.dateTime(minute), 1L, Long::sum);
      insertedHours.merge(BenchContext.dateTime(hour), 1L, Long::sum);
    }
    int deltaMismatches = 0;
    List<List<String>> table = new ArrayList<>();
    TreeSet<String> keys = new TreeSet<>(minutesAfter.keySet());
    keys.addAll(inserted.keySet());
    for (String m : keys) {
      long[] before = minutesBefore.getOrDefault(m, new long[4]);
      long[] after = minutesAfter.getOrDefault(m, new long[4]);
      long expected = inserted.getOrDefault(m, 0L);
      boolean ok = true;
      for (int c = 0; c < 4; c++) {
        if (after[c] - before[c] != expected) {
          ok = false;
        }
      }
      if (!ok) {
        deltaMismatches++;
      }
      table.add(
          List.of(
              m,
              Long.toString(expected),
              Long.toString(after[0] - before[0]),
              Long.toString(after[1] - before[1]),
              Long.toString(after[2] - before[2]),
              Long.toString(after[3] - before[3]),
              ok ? "ok" : "MISMATCH"));
    }
    for (Map.Entry<String, Long> h : insertedHours.entrySet()) {
      long[] before = hoursBefore.getOrDefault(h.getKey(), new long[2]);
      long[] after = hoursAfter.getOrDefault(h.getKey(), new long[2]);
      if (after[0] - before[0] != h.getValue() || after[1] - before[1] != h.getValue()) {
        deltaMismatches++;
      }
    }
    long windowRows =
        Tsv.count(ctx.runner().stepOk(ctx.file("Q12"), "window_minute", window, Map.of()).body());
    Parameters q12 = ctx.parametersOf("Q12");
    VariantResult full =
        ctx.runner().run(ctx.file("Q12"), ctx.file("Q12").variant("minute_30d"), q12);
    double fullRows = full.metric("rows");
    return new ProcedureResult("Q16", f.title(), f.criterion())
        .metric("rows", windowRows + (Double.isNaN(fullRows) ? Double.NaN : fullRows))
        .metric("delta_mismatches", deltaMismatches)
        .metric("late_rows", txs.size())
        .table(
            new Table(
                "Per-minute increase after the late insert (expected = inserted rows of the minute)",
                List.of(
                    "minute",
                    "inserted",
                    "xlog",
                    "app_stat_1m",
                    "service_stat_1m",
                    "instance_stat_1m",
                    "check"),
                table))
        .note(
            txs.size()
                + " rows with ts in "
                + BenchContext.dateTime(from)
                + " .. "
                + BenchContext.dateTime(to)
                + " inserted in "
                + blocks
                + " block(s) after the end of the data; Q12 window rows "
                + windowRows
                + ", Q12 minute_30d rows "
                + (long) fullRows
                + " ("
                + full.verdict()
                + ")")
        .judge();
  }

  private static Map<String, long[]> minutes(BenchContext ctx, QueryFile f, Parameters window) {
    Map<String, long[]> out = new LinkedHashMap<>();
    for (List<String> r :
        Tsv.rows(ctx.runner().stepOk(f, "window_minutes", window, Map.of()).body())) {
      out.put(
          normalize(r.get(0)),
          new long[] {
            Long.parseLong(r.get(1)),
            Long.parseLong(r.get(2)),
            Long.parseLong(r.get(3)),
            Long.parseLong(r.get(4))
          });
    }
    return out;
  }

  private static Map<String, long[]> hours(BenchContext ctx, QueryFile f, Parameters window) {
    Map<String, long[]> out = new LinkedHashMap<>();
    for (List<String> r :
        Tsv.rows(ctx.runner().stepOk(f, "window_hours", window, Map.of()).body())) {
      out.put(normalize(r.get(0)), new long[] {Long.parseLong(r.get(1)), Long.parseLong(r.get(2))});
    }
    return out;
  }

  private static String normalize(String dateTime) {
    return BenchContext.dateTime(
        LocalDateTime.parse(dateTime.substring(0, 19).replace(' ', 'T')).toInstant(ZoneOffset.UTC));
  }
}
