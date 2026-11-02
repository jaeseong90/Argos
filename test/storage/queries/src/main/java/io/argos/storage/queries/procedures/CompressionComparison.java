/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.QueryLog;
import io.argos.storage.queries.Stats;
import io.argos.storage.queries.Tsv;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Q20 compression (13 §14, N-19, T-ST-041, T-ST-050): compression ratio and bytes per row of every
 * table of the loaded data, and a sample of the five raw tables in six variants of their V001
 * definition with the insert CPU of the copy: {@code base} is V001 as deployed (ADR-0007 C-54:
 * server default ZSTD(1), adjusted column codecs); {@code lz4} is the pre-ADR-0007 V001 ({@link
 * LegacyCodecs}, default LZ4, the WO-03 {@code base}); {@code lz4_nocodec}, {@code lz4cols_zstd}
 * and {@code nocodec} are the WO-03 variants {@code nocodec}, {@code zstd} and {@code zstd_nocodec}
 * (column codecs on/off x default codec LZ4/ZSTD(1)); {@code proposed} is the WO-03 PoC proposal
 * ({@link ProposedCodecs} applied to the pre-ADR-0007 definition), which differs from {@code base}
 * only by {@code CODEC(Delta, ZSTD(1))} on profile_step.tx_id.
 *
 * <p>Controls: each table is copied from its own latest full 24 hours (or its whole range when it
 * holds less: profile_step keeps fewer days than xlog); after the copy of a variant the merges are
 * left to settle (no merge running and the same part count twice, bounded) and the final part count
 * is recorded; every variant is copied {@code repeats} times (the order reversed on even
 * repetitions) and the spread between repetitions is reported.
 *
 * <p>Metrics: {@code ratio_<table>} and {@code bytes_per_row_<table>} of the loaded data, {@code
 * ratio_<variant>_<table>} (mean of the repetitions) and {@code ratio_<variant>_<table>_spread}
 * (max - min) of the variants.
 */
public final class CompressionComparison {

  /** The raw tables compared in variants. */
  public static final List<String> RAW =
      List.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw");

  private static final Map<String, String> VARIANTS = new LinkedHashMap<>();

  static {
    VARIANTS.put(
        "base",
        "V001 (ADR-0007 C-54): server default ZSTD(1), column codecs with ZSTD(1) stages, xlog"
            + " trace_id and parent_span_id without CODEC(NONE), xlog tx_id Delta + ZSTD(1)");
    VARIANTS.put(
        "lz4",
        "V001 before ADR-0007 (WO-03 base): column codecs with LZ4 stages, xlog trace_id and"
            + " parent_span_id CODEC(NONE), default LZ4");
    VARIANTS.put("lz4_nocodec", "no column codecs, default LZ4 (WO-03 nocodec)");
    VARIANTS.put("lz4cols_zstd", "pre-ADR-0007 column codecs, default ZSTD(1) (WO-03 zstd)");
    VARIANTS.put("nocodec", "no column codecs, default ZSTD(1) (WO-03 zstd_nocodec)");
    VARIANTS.put(
        "proposed",
        "WO-03 PoC proposal (12.5) on the pre-ADR-0007 V001: default ZSTD(1), LZ4 stages ->"
            + " ZSTD(1), xlog parent_span_id and trace_id without CODEC(NONE), tx_id Delta +"
            + " ZSTD(1) in xlog and profile_step (= base + profile_step.tx_id Delta)");
  }

  private CompressionComparison() {}

  /**
   * Options.
   *
   * @param repeats copies per variant and table
   * @param settle longest wait for the merges of a variant to settle
   */
  public record Options(int repeats, Duration settle) {

    /** Bench values: 2 repetitions, merges settle within 5 minutes. */
    public static Options bench() {
      return new Options(2, Duration.ofMinutes(5));
    }
  }

  /** Runs Q20 with the bench options. */
  public static ProcedureResult run(BenchContext ctx) {
    return run(ctx, Options.bench());
  }

  /** Runs Q20. */
  public static ProcedureResult run(BenchContext ctx, Options o) {
    QueryFile f = ctx.file("Q20");
    ProcedureResult r = new ProcedureResult("Q20", f.title(), f.criterion());
    SchemaParameters schema = ctx.schema();
    String prefix = schema.tablePrefix();
    Parameters base = ctx.runner().baseParameters();

    List<List<String>> dataset = new ArrayList<>();
    for (List<String> row :
        Tsv.rows(ctx.runner().stepOk(f, "dataset_sizes", base, Map.of()).body())) {
      String table = row.get(0).substring(prefix.length());
      if (table.startsWith("q1") || table.startsWith("q2") || table.startsWith("bv_")) {
        continue; // leftovers of the procedures' copies
      }
      long rows = Long.parseLong(row.get(1));
      long uncompressed = Long.parseLong(row.get(2));
      long compressed = Long.parseLong(row.get(3));
      double ratio = compressed == 0 ? Double.NaN : uncompressed / (double) compressed;
      double perRow = rows == 0 ? Double.NaN : compressed / (double) rows;
      r.metric("ratio_" + table, ratio);
      r.metric("bytes_per_row_" + table, perRow);
      dataset.add(
          List.of(
              table,
              row.get(1),
              row.get(2),
              row.get(3),
              fmt(ratio),
              fmt(perRow),
              row.get(4),
              row.get(6)));
    }
    r.table(
        new Table(
            "Loaded data per table (system.parts, active parts)",
            List.of(
                "table",
                "rows",
                "uncompressed B",
                "compressed B",
                "ratio",
                "B/row",
                "skip index B",
                "parts"),
            dataset));

    // The copied range of each table: its latest full 24 hours, or all of it.
    Map<String, Parameters> ranges = new LinkedHashMap<>();
    List<List<String>> rangeRows = new ArrayList<>();
    for (String table : RAW) {
      List<String> row =
          Tsv.rows(ctx.runner().stepOk(f, "table_range", base, Map.of("table", table)).body())
              .get(0);
      long count = Long.parseLong(row.get(2));
      if (count == 0) {
        continue;
      }
      Instant min = instant(row.get(0));
      Instant max = instant(row.get(1));
      boolean full = !max.minus(Duration.ofDays(1)).isBefore(min);
      Instant from = full ? max.minus(Duration.ofDays(1)).plusMillis(1) : min;
      ranges.put(
          table,
          base.with(
              Map.of("from", BenchContext.dateTime64(from), "to", BenchContext.dateTime64(max))));
      rangeRows.add(
          List.of(
              table,
              BenchContext.dateTime64(from),
              BenchContext.dateTime64(max),
              full ? "latest 24 h" : "whole range (less than 24 h)"));
    }

    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    List<List<String>> variants = new ArrayList<>();
    Map<String, List<Double>> ratios = new LinkedHashMap<>();
    List<String> order = new ArrayList<>(VARIANTS.keySet());
    try {
      for (int rep = 1; rep <= o.repeats(); rep++) {
        List<String> repOrder = new ArrayList<>(order);
        if (rep % 2 == 0) {
          Collections.reverse(repOrder);
        }
        for (String variant : repOrder) {
          String vp = "q20_" + variant + "_";
          SchemaParameters vs = schema.withTablePrefix(prefix + vp);
          Map<String, Measurement> fills = new LinkedHashMap<>();
          for (String table : ranges.keySet()) {
            ctx.runner().stepOk(f, "variant_drop", base, Map.of("variant", vp, "table", table));
            ctx.runner()
                .statement(
                    Account.MIGRATOR,
                    "Q20/create_" + variant + "_" + table,
                    variantStatement(renderer, vs, table, variant),
                    Map.of());
            fills.put(
                table,
                ctx.runner()
                    .stepOk(
                        f,
                        "variant_fill",
                        ranges.get(table),
                        Map.of("variant", vp, "table", table)));
          }
          String settled = settle(ctx, f, base.with("vprefix", prefix + vp), o.settle());
          // Sizes of this variant, then drop it: one variant on disk at a time (the local disk
          // budget).
          Map<String, long[]> sizes = new LinkedHashMap<>();
          for (List<String> row :
              Tsv.rows(
                  ctx.runner()
                      .stepOk(f, "dataset_sizes", base.with("prefix", prefix + vp), Map.of())
                      .body())) {
            sizes.put(
                row.get(0).substring((prefix + vp).length()),
                new long[] {
                  Long.parseLong(row.get(1)),
                  Long.parseLong(row.get(2)),
                  Long.parseLong(row.get(3)),
                  Long.parseLong(row.get(6))
                });
          }
          Map<String, QueryLog.Entry> log =
              ctx.runner().collect(fills.values().stream().map(Measurement::queryId).toList());
          for (String table : ranges.keySet()) {
            long[] s = sizes.getOrDefault(table, new long[4]);
            double ratio = s[2] == 0 ? Double.NaN : s[1] / (double) s[2];
            double perRow = s[0] == 0 ? Double.NaN : s[2] / (double) s[0];
            Measurement fill = fills.get(table);
            QueryLog.Entry e = log.get(fill.queryId());
            ratios.computeIfAbsent(variant + "_" + table, k -> new ArrayList<>()).add(ratio);
            variants.add(
                List.of(
                    table,
                    variant,
                    Integer.toString(rep),
                    Long.toString(s[0]),
                    Long.toString(s[2]),
                    fmt(ratio),
                    fmt(perRow),
                    Long.toString(s[3]),
                    e == null ? "-" : fmt(e.cpuMs()),
                    e == null ? "-" : Long.toString(e.durationMs()),
                    pct(ctx.runner().cpuLoad(fill)),
                    settled));
          }
          for (String table : ranges.keySet()) {
            ctx.runner().stepOk(f, "variant_drop", base, Map.of("variant", vp, "table", table));
          }
        }
      }
    } finally {
      for (String variant : VARIANTS.keySet()) {
        for (String table : RAW) {
          ctx.runner()
              .step(
                  f,
                  "variant_drop",
                  base,
                  Map.of("variant", "q20_" + variant + "_", "table", table));
        }
      }
    }
    List<List<String>> spread = new ArrayList<>();
    for (String table : ranges.keySet()) {
      for (String variant : VARIANTS.keySet()) {
        List<Double> v = ratios.getOrDefault(variant + "_" + table, List.of());
        if (v.isEmpty()) {
          continue;
        }
        double min = v.stream().mapToDouble(d -> d).min().orElse(Double.NaN);
        double max = Stats.max(v);
        r.metric("ratio_" + variant + "_" + table, Stats.mean(v));
        r.metric("ratio_" + variant + "_" + table + "_spread", max - min);
        List<String> row = new ArrayList<>();
        row.add(table);
        row.add(variant);
        for (Double d : v) {
          row.add(fmt(d));
        }
        while (row.size() < 2 + o.repeats()) {
          row.add("-");
        }
        row.add(fmt(max - min));
        row.add(min == 0 ? "-" : String.format(Locale.ROOT, "%.1f %%", (max - min) / min * 100));
        spread.add(row);
      }
    }
    List<String> spreadHeaders = new ArrayList<>(List.of("table", "variant"));
    for (int rep = 1; rep <= o.repeats(); rep++) {
      spreadHeaders.add("ratio rep " + rep);
    }
    spreadHeaders.add("max - min");
    spreadHeaders.add("spread / min");
    List<List<String>> legend = new ArrayList<>();
    VARIANTS.forEach((k, v) -> legend.add(List.of(k, v)));
    return r.table(
            new Table(
                "Copied range per table (each its latest full 24 hours, else its whole range)",
                List.of("table", "from", "to", "rule"),
                rangeRows))
        .table(
            new Table(
                "Variants: INSERT ... SELECT copies (insert CPU from system.query_log; parts after"
                    + " the merges settled)",
                List.of(
                    "table",
                    "variant",
                    "rep",
                    "rows",
                    "compressed B",
                    "ratio",
                    "B/row",
                    "parts",
                    "insert CPU ms",
                    "insert ms",
                    "machine CPU",
                    "merges"),
                variants))
        .table(new Table("Ratio per repetition", spreadHeaders, spread))
        .table(new Table("Variant legend", List.of("variant", "definition"), legend))
        .judge();
  }

  /** The CREATE TABLE of {@code table} in a variant. */
  static String variantStatement(
      SchemaRenderer renderer, SchemaParameters vs, String table, String variant) {
    String create = createStatement(renderer, vs, table);
    return switch (variant) {
      case "base" -> create; // the server default of config.d applies (ZSTD(1), C-54)
      case "lz4" ->
          withSetting(LegacyCodecs.apply(create, table), "default_compression_codec = 'LZ4'");
      case "lz4_nocodec" ->
          withSetting(
              SchemaRenderer.withoutColumnCodecs(create), "default_compression_codec = 'LZ4'");
      case "lz4cols_zstd" ->
          withSetting(LegacyCodecs.apply(create, table), "default_compression_codec = 'ZSTD(1)'");
      case "nocodec" ->
          withSetting(
              SchemaRenderer.withoutColumnCodecs(create), "default_compression_codec = 'ZSTD(1)'");
      case "proposed" -> ProposedCodecs.apply(LegacyCodecs.apply(create, table), table);
      default -> throw new IllegalArgumentException("Unknown Q20 variant: " + variant);
    };
  }

  /** Waits until the variant has no merge and the same part count twice; what it saw. */
  private static String settle(BenchContext ctx, QueryFile f, Parameters p, Duration max) {
    long start = System.nanoTime();
    long previous = -1;
    while (true) {
      List<String> row =
          Tsv.rows(ctx.runner().stepOk(f, "variant_parts", p, Map.of()).body()).get(0);
      long parts = Long.parseLong(row.get(0));
      long merges = Long.parseLong(row.get(1));
      double waited = (System.nanoTime() - start) / 1e9;
      if (merges == 0 && parts == previous) {
        return String.format(Locale.ROOT, "settled after %.0f s", waited);
      }
      if (System.nanoTime() - start > max.toNanos()) {
        return String.format(
            Locale.ROOT, "not settled after %.0f s (%d merges running)", waited, merges);
      }
      previous = merges == 0 ? parts : -1;
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return "interrupted";
      }
    }
  }

  static String createStatement(SchemaRenderer renderer, SchemaParameters schema, String table) {
    String head = "CREATE TABLE IF NOT EXISTS " + schema.table(table) + "\n";
    return renderer.renderAll(schema).stream()
        .filter(s -> s.startsWith(head))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("No CREATE TABLE " + schema.table(table) + " in V001"));
  }

  static String withSetting(String create, String setting) {
    int at = create.lastIndexOf("SETTINGS ");
    if (at < 0) {
      return create + "\nSETTINGS " + setting;
    }
    return create.strip() + ",\n         " + setting;
  }

  private static Instant instant(String value) {
    String v = value.length() > 23 ? value.substring(0, 23) : value;
    return LocalDateTime.parse(v.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
  }

  private static String fmt(double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v);
  }

  private static String pct(double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.0f %%", v * 100);
  }
}
