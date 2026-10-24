/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.Tsv;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Q19 migration rehearsal on the loaded data (13 §13.2, N-22, T-ST-052): ADD/DROP COLUMN, MODIFY
 * TTL with materialize 0, the same TTL with materialize 1 (no-op control: ClickHouse recalculates
 * nothing for an unchanged expression), a real retention change (+1 day, materialize 1, waited) and
 * its metadata-only restore, and the four steps of an MV replacement for service_stat_1m whose DDL
 * is rendered from deploy/clickhouse (the V001 table statement under the name service_stat_1m_v2,
 * the rollups/service_stat_1m.sql SELECT for the view and the rebuild). Metrics: {@code step_ms}
 * (slowest judged step; the restore is recorded only), {@code ttl_change_ms}, {@code xlog_rows} and
 * {@code mv_mismatches} (rows where v1 and v2 disagree on the backfilled day).
 */
public final class MigrationRehearsal {

  private MigrationRehearsal() {}

  /** Runs Q19. */
  public static ProcedureResult run(BenchContext ctx) {
    QueryFile f = ctx.file("Q19");
    SchemaParameters schema = ctx.schema();
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    LocalDate day = ctx.date("full_day");
    Parameters p =
        ctx.runner()
            .baseParameters()
            .with(Map.of("day", day.toString(), "table", schema.tablePrefix() + "xlog"));
    List<Step> steps = new ArrayList<>();
    double slowest = 0;
    long mismatches;
    boolean ttlChanged = false;
    List<String> size = Tsv.rows(ctx.runner().stepOk(f, "xlog_size", p, Map.of()).body()).get(0);
    long xlogRows = Long.parseLong(size.get(0));
    double ttlChange = Double.NaN;
    try {
      for (String step :
          List.of("add_column", "drop_column", "ttl_keep_0", "ttl_noop_1", "ttl_change_1")) {
        ttlChanged |= step.equals("ttl_change_1");
        Measurement m = ctx.runner().stepOk(f, step, p, Map.of());
        steps.add(new Step(step, true, List.of(m)));
        if (step.equals("ttl_change_1")) {
          ttlChange = m.wallMs();
        }
      }
      Measurement restore = ctx.runner().stepOk(f, "ttl_restore_0", p, Map.of());
      ttlChanged = false;
      steps.add(new Step("ttl_restore_0 (recorded, not judged)", false, List.of(restore)));
      String v1 = schema.table("service_stat_1m");
      String v2 = schema.table("service_stat_1m_v2");
      String create =
          renderer.renderAll(schema).stream()
              .filter(s -> s.startsWith("CREATE TABLE IF NOT EXISTS " + v1 + "\n"))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("No CREATE TABLE " + v1 + " in V001"))
              .replace(
                  "CREATE TABLE IF NOT EXISTS " + v1 + "\n",
                  "CREATE TABLE IF NOT EXISTS " + v2 + "\n");
      String view =
          "CREATE MATERIALIZED VIEW IF NOT EXISTS "
              + v2
              + "_mv TO "
              + v2
              + "\nSQL SECURITY DEFINER AS "
              + renderer.rollupSelect("service_stat_1m", schema, "");
      String backfill =
          renderer.rebuildInsert("service_stat_1m", v2, schema, "toDate(ts) = '" + day + "'");
      Measurement m1 =
          ctx.runner().statement(Account.MIGRATOR, "Q19/mv_1_create_table", create, Map.of());
      Measurement m2 =
          ctx.runner().statement(Account.MIGRATOR, "Q19/mv_1_create_view", view, Map.of());
      steps.add(new Step("MV step 1: create service_stat_1m_v2 + view", true, List.of(m1, m2)));
      Measurement m3 =
          ctx.runner()
              .statement(
                  Account.MIGRATOR,
                  "Q19/mv_2_backfill",
                  backfill,
                  Map.of("max_execution_time", "3600"));
      steps.add(
          new Step("MV step 2: backfill " + day + " (rebuild statement, D-14)", true, List.of(m3)));
      Measurement m4 = ctx.runner().stepOk(f, "mv_compare", p, Map.of());
      mismatches = Tsv.count(m4.body());
      steps.add(
          new Step(
              "MV step 3: switch check v1 = v2 on "
                  + day
                  + " ("
                  + mismatches
                  + " mismatching rows)",
              true,
              List.of(m4)));
      Measurement m5 = ctx.runner().stepOk(f, "mv_drop_view", p, Map.of());
      Measurement m6 = ctx.runner().stepOk(f, "mv_drop_table", p, Map.of());
      steps.add(new Step("MV step 4: drop (rehearsal objects)", true, List.of(m5, m6)));
    } finally {
      if (ttlChanged) {
        ctx.runner().step(f, "ttl_restore_0", p, Map.of()); // the retention must not stay at +1
      }
      ctx.runner().step(f, "drop_column", p, Map.of());
      ctx.runner().step(f, "mv_drop_view", p, Map.of());
      ctx.runner().step(f, "mv_drop_table", p, Map.of());
    }
    List<List<String>> rows = new ArrayList<>();
    for (Step step : steps) {
      if (step.judged()) {
        slowest = Math.max(slowest, step.ms());
      }
      // Machine CPU load over the statements of the step (the sample after the end exists now).
      double load =
          ctx.runner()
              .cpuLoad(
                  step.runs().get(0).startNanos(),
                  step.runs().get(step.runs().size() - 1).endNanos());
      rows.add(
          List.of(
              step.name(),
              ms(step.ms()),
              Double.isNaN(load) ? "-" : String.format(Locale.ROOT, "%.0f %%", load * 100)));
    }
    return new ProcedureResult("Q19", f.title(), f.criterion())
        .metric("step_ms", slowest)
        .metric("ttl_change_ms", ttlChange)
        .metric("xlog_rows", xlogRows)
        .metric("mv_mismatches", mismatches)
        .table(
            new Table(
                "Migration steps (client wall time; xlog "
                    + xlogRows
                    + " rows in "
                    + size.get(1)
                    + " parts, "
                    + size.get(2)
                    + " B)",
                List.of("step", "ms", "machine CPU"),
                rows))
        .judge();
  }

  /** A step of the table: its statements and whether the 10 minute criterion applies. */
  private record Step(String name, boolean judged, List<Measurement> runs) {
    double ms() {
      return runs.stream().mapToDouble(Measurement::wallMs).sum();
    }
  }

  private static String ms(double v) {
    return String.format(Locale.ROOT, "%.0f", v);
  }
}
