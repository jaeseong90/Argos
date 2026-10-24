/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Criterion;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.Tsv;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Q11 disk cost of {@code xlog.sql_ids} (A-10): one day of xlog copied with the xlog sort key into
 * a table with sql_ids and its bloom index and into one without; the difference of the compressed
 * data and the skip index is the cost of the column. Metrics: {@code sql_ids_bytes_per_row}, {@code
 * sql_ids_gb_per_day_500tps} (x 43.2 million transactions per day), {@code
 * sql_ids_index_bytes_per_row}.
 */
public final class SqlIdsCost {

  /** Transactions per day at 500 TPS (13 §14 base row of the capacity table). */
  public static final double ROWS_PER_DAY_500_TPS = 500.0 * 86_400;

  private SqlIdsCost() {}

  /** Runs the measurement on the last full day. */
  public static ProcedureResult run(BenchContext ctx) {
    QueryFile f = ctx.file("Q11");
    LocalDate day = ctx.date("full_day");
    String prefix = ctx.schema().tablePrefix();
    Parameters p =
        ctx.runner()
            .baseParameters()
            .with(
                Map.of(
                    "day", day.toString(),
                    "with_table", prefix + "q11_with",
                    "without_table", prefix + "q11_without"));
    Map<String, long[]> sizes = new LinkedHashMap<>();
    try {
      for (String step :
          List.of(
              "cost_drop_with",
              "cost_drop_without",
              "cost_create_with",
              "cost_create_without",
              "cost_fill_with",
              "cost_fill_without")) {
        ctx.runner().stepOk(f, step, p, Map.of());
      }
      for (List<String> row : Tsv.rows(ctx.runner().stepOk(f, "cost_sizes", p, Map.of()).body())) {
        sizes.put(
            row.get(0).substring(prefix.length()),
            new long[] {
              Long.parseLong(row.get(1)),
              Long.parseLong(row.get(2)),
              Long.parseLong(row.get(3)),
              Long.parseLong(row.get(4))
            });
      }
    } finally {
      ctx.runner().step(f, "cost_drop_with", p, Map.of());
      ctx.runner().step(f, "cost_drop_without", p, Map.of());
    }
    long[] with = sizes.getOrDefault("q11_with", new long[4]);
    long[] without = sizes.getOrDefault("q11_without", new long[4]);
    double rows = with[0];
    double data = rows == 0 ? Double.NaN : (with[1] - without[1]) / rows;
    double index = rows == 0 ? Double.NaN : with[3] / rows;
    double perRow = data + index;
    double uncompressed = rows == 0 ? Double.NaN : (with[2] - without[2]) / rows;
    List<List<String>> table = new ArrayList<>();
    table.add(
        List.of(
            "with sql_ids + idx_sql",
            Long.toString(with[0]),
            Long.toString(with[1]),
            Long.toString(with[3])));
    table.add(
        List.of(
            "without",
            Long.toString(without[0]),
            Long.toString(without[1]),
            Long.toString(without[3])));
    return new ProcedureResult("Q11/cost", "sql_ids disk cost (A-10)", Criterion.RECORD)
        .metric("sql_ids_bytes_per_row", perRow)
        .metric("sql_ids_data_bytes_per_row", data)
        .metric("sql_ids_index_bytes_per_row", index)
        .metric("sql_ids_uncompressed_bytes_per_row", uncompressed)
        .metric("sql_ids_gb_per_day_500tps", perRow * ROWS_PER_DAY_500_TPS / 1e9)
        .table(
            new Table(
                "One day (" + day + ") of xlog keys with and without sql_ids",
                List.of("copy", "rows", "data compressed B", "skip index B"),
                table))
        .note(
            String.format(
                Locale.ROOT,
                "sql_ids costs %.2f B/row compressed (%.2f data + %.2f bloom index; %.1f B/row uncompressed)"
                    + " = %.3f GB/day at 500 TPS",
                perRow,
                data,
                index,
                uncompressed,
                perRow * ROWS_PER_DAY_500_TPS / 1e9))
        .judge();
  }
}
