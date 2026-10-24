/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Criterion;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.QueryLog;
import io.argos.storage.queries.SqlTemplate;
import io.argos.storage.queries.Stats;
import io.argos.storage.queries.Tsv;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bloom filter parameters for Q5 (tx_id without a time range), Q6 (trace_id over the whole range),
 * Q4/N-24 (user id) and Q11 (has(sql_ids)): the same days of xlog are copied into one table per
 * (false-positive rate, GRANULARITY) of the five xlog bloom indexes (V001 rendered with the
 * variant), then each lookup runs without a time range on every copy (cold + warm runs as
 * argos_reader). Reported per variant: skip index size (system.parts), warm median time, and the
 * marks and rows that survived the index (system.query_log ProfileEvents SelectedMarks, read_rows).
 * Recorded for the bloom decision of Gate 0.
 */
public final class BloomVariants {

  /** fpp x GRANULARITY of 13 §15 Q5. */
  public static final List<double[]> VARIANTS =
      List.of(
          new double[] {0.01, 1},
          new double[] {0.01, 4},
          new double[] {0.025, 1},
          new double[] {0.025, 4});

  private static final List<String> LOOKUPS =
      List.of("bloom_tx", "bloom_trace", "bloom_user", "bloom_sql");

  private BloomVariants() {}

  /**
   * Runs the comparison on the last {@code days} full days.
   *
   * @param ctx context
   * @param days days copied per variant
   * @param warmRuns warm runs per lookup
   */
  public static ProcedureResult run(BenchContext ctx, int days, int warmRuns) {
    QueryFile f = ctx.file("Q05");
    SchemaParameters schema = ctx.schema();
    LocalDate last = ctx.date("full_day");
    LocalDate first = ctx.date("first_day");
    LocalDate from = last.minusDays(days - 1L).isBefore(first) ? first : last.minusDays(days - 1L);
    Instant fromT = from.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant toT = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Parameters range =
        ctx.shared()
            .with(
                Map.of(
                    "from", BenchContext.dateTime(fromT),
                    "to", BenchContext.dateTime(toT),
                    "database", schema.database()));
    Parameters targets = range.with(probe(ctx, f, range));
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    List<List<String>> rows = new ArrayList<>();
    Map<String, String> warmIds = new LinkedHashMap<>();
    Map<String, Double> warmMs = new LinkedHashMap<>();
    Map<String, Long> indexBytes = new LinkedHashMap<>();
    Map<String, Long> copiedRows = new LinkedHashMap<>();
    try {
      for (double[] v : VARIANTS) {
        String name = name(v);
        String vp = name + "_";
        SchemaParameters vs = schema.withTablePrefix(schema.tablePrefix() + vp);
        for (String index : SchemaParameters.BLOOM_INDEXES) {
          vs = vs.withBloom(index, v[0], (int) v[1]);
        }
        ctx.runner().stepOk(f, "bloom_drop", targets, Map.of("variant", vp));
        ctx.runner()
            .statement(
                Account.MIGRATOR,
                "Q05/bloom_create_" + name,
                CompressionComparison.createStatement(renderer, vs, "xlog"),
                Map.of());
        ctx.runner().stepOk(f, "bloom_fill", targets, Map.of("variant", vp));
        Parameters sizeParams = targets.with("table", vs.tablePrefix() + "xlog");
        List<String> size =
            Tsv.rows(ctx.runner().stepOk(f, "bloom_sizes", sizeParams, Map.of()).body()).get(0);
        copiedRows.put(name, Long.parseLong(size.get(1)));
        indexBytes.put(name, Long.parseLong(size.get(2)));
        for (String lookup : LOOKUPS) {
          QueryFile.Section s = f.step(lookup);
          String sql = SqlTemplate.render(s.sql(), ctx.runner().textual(Map.of("variant", vp)));
          List<Double> warm = new ArrayList<>();
          String lastId = null;
          for (int i = 0; i <= warmRuns; i++) {
            Measurement m =
                ctx.runner()
                    .execute(
                        Account.READER,
                        sql,
                        Map.of("max_execution_time", "10"),
                        targets,
                        "Q05/" + lookup + "_" + name,
                        i == 0 ? "cold" : "warm" + i,
                        false);
            if (m.failed()) {
              throw new IllegalStateException(m.label() + " failed: " + m.errorMessage());
            }
            if (i > 0) {
              warm.add(m.wallMs());
              lastId = m.queryId();
            }
          }
          warmMs.put(name + "|" + lookup, Stats.median(warm));
          warmIds.put(name + "|" + lookup, lastId);
        }
        // One copy on disk at a time (a day of xlog per variant; the local disk budget).
        ctx.runner().stepOk(f, "bloom_drop", targets, Map.of("variant", vp));
      }
      Map<String, QueryLog.Entry> log = ctx.runner().collect(warmIds.values());
      for (double[] v : VARIANTS) {
        String name = name(v);
        List<String> row = new ArrayList<>();
        row.add(String.format(Locale.ROOT, "%.3f", v[0]));
        row.add(Integer.toString((int) v[1]));
        row.add(Long.toString(copiedRows.get(name)));
        row.add(Long.toString(indexBytes.get(name)));
        for (String lookup : LOOKUPS) {
          QueryLog.Entry e = log.get(warmIds.get(name + "|" + lookup));
          row.add(
              String.format(Locale.ROOT, "%.1f", warmMs.get(name + "|" + lookup))
                  + " / "
                  + (e == null ? "-" : e.selectedMarks() + " marks, " + e.readRows() + " rows"));
        }
        rows.add(row);
      }
    } finally {
      for (double[] v : VARIANTS) {
        ctx.runner().step(f, "bloom_drop", targets, Map.of("variant", name(v) + "_"));
      }
    }
    return new ProcedureResult(
            "Q05/bloom", "Bloom filter variants (Q5, Q6, Q4 user id, Q11)", Criterion.RECORD)
        .table(
            new Table(
                "xlog copies of "
                    + from
                    + " .. "
                    + last
                    + "; lookups without a time range (warm median ms / marks and rows read)",
                List.of(
                    "fpp",
                    "granularity",
                    "rows",
                    "skip index B (5 indexes)",
                    "tx_id",
                    "trace_id",
                    "user_id",
                    "has(sql_ids)"),
                rows))
        .note(
            "targets: "
                + targets.asMap().entrySet().stream()
                    .filter(e -> e.getKey().startsWith("b"))
                    .toList())
        .judge();
  }

  private static Map<String, String> probe(BenchContext ctx, QueryFile f, Parameters range) {
    String body = ctx.runner().stepOk(f, "bloom_targets", range, Map.of()).body();
    List<List<String>> raw = Tsv.rawRows(body);
    if (raw.isEmpty()) {
      throw new IllegalStateException("bloom_targets returned no row");
    }
    List<String> r = raw.get(0);
    if (r.contains("\\N")) {
      throw new IllegalStateException(
          "bloom_targets found no lookup target in the copied days (NULL in " + r + ")");
    }
    return Map.of("btx", r.get(0), "btrace", r.get(1), "buid", r.get(2), "bsql", r.get(3));
  }

  private static String name(double[] v) {
    return String.format(Locale.ROOT, "bv_f%04d_g%d", Math.round(v[0] * 10000), (int) v[1]);
  }
}
