/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.QueryFile.Kind;
import io.argos.storage.queries.procedures.BloomVariants;
import io.argos.storage.queries.procedures.CompressionComparison;
import io.argos.storage.queries.procedures.ConcurrentLoad;
import io.argos.storage.queries.procedures.DedupReinsert;
import io.argos.storage.queries.procedures.LateArrival;
import io.argos.storage.queries.procedures.MigrationRehearsal;
import io.argos.storage.queries.procedures.PercentileAccuracy;
import io.argos.storage.queries.procedures.RetentionOps;
import io.argos.storage.queries.procedures.SqlIdsCost;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs a plan (which query files and procedures) and collects the results: the timing and
 * consistency files with the runner, then the procedures, then system.query_log.
 */
public final class BenchRun {

  /** Procedure names of a plan. */
  public static final List<String> PROCEDURES =
      List.of("Q05/bloom", "Q11/cost", "Q14", "Q15", "Q16", "Q17", "Q18", "Q19", "Q20");

  private BenchRun() {}

  /**
   * What to run.
   *
   * @param files timing and consistency files (Q01..Q13)
   * @param procedures procedures of {@link #PROCEDURES}
   * @param q14 percentile accuracy options
   * @param q15Transactions transactions per Q15 block
   * @param q15Repeats re-inserts per Q15 phase
   * @param q16Rows late rows of Q16
   * @param q17 concurrent load options
   * @param q18 retention operation options (waits, repetitions, copied hours)
   * @param q20 compression comparison options (repetitions, merge settle)
   * @param bloomDays days copied per bloom variant
   */
  public record Plan(
      Set<String> files,
      Set<String> procedures,
      PercentileAccuracy.Options q14,
      int q15Transactions,
      int q15Repeats,
      int q16Rows,
      ConcurrentLoad.Options q17,
      RetentionOps.Options q18,
      CompressionComparison.Options q20,
      int bloomDays) {

    /** Every file and procedure with the 13 §15 sizes. */
    public static Plan full(String ingestion) {
      Set<String> files = new java.util.TreeSet<>();
      for (int i = 1; i <= 13; i++) {
        files.add(String.format(Locale.ROOT, "Q%02d", i));
      }
      return new Plan(
          files,
          Set.copyOf(PROCEDURES),
          PercentileAccuracy.Options.bench(),
          20_000,
          100,
          20_000,
          ConcurrentLoad.Options.bench(ingestion),
          RetentionOps.Options.bench(),
          CompressionComparison.Options.bench(),
          3);
    }
  }

  /** Runs the plan. Procedure failures are recorded, not thrown. */
  public static BenchResults run(BenchContext ctx, Plan plan, MachineLoad load) {
    BenchResults results = new BenchResults().load(load);
    long start = System.nanoTime();
    environment(ctx, load, results);
    results.parameters(ctx.shared());
    try {
      results.data(
          dataTable(
              ctx.runner()
                  .stepOk(ctx.file("Q20"), "dataset_sizes", ctx.runner().baseParameters(), Map.of())
                  .body()));
    } catch (RuntimeException e) {
      results.error("data sizes: " + e.getMessage());
    }
    for (QueryFile f : ctx.catalog().queries()) {
      if (!plan.files().contains(f.id()) || f.kind() == Kind.PROCEDURE) {
        continue;
      }
      try {
        Parameters p = ctx.runner().resolve(f, ctx.shared());
        results.variants(ctx.runner().runAll(f, p));
      } catch (RuntimeException e) {
        results.error(f.id() + ": " + e.getMessage());
      }
    }
    for (String proc : PROCEDURES) {
      if (!plan.procedures().contains(proc)) {
        continue;
      }
      try {
        results.procedures(procedure(ctx, plan, proc));
      } catch (RuntimeException e) {
        results.procedures(
            List.of(new ProcedureResult(proc, proc, Criterion.RECORD).error(e.toString())));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        results.error(proc + ": interrupted");
        break;
      }
    }
    Map<String, QueryLog.Entry> log = ctx.runner().collectQueryLog();
    results.log(log, ctx.runner().stepRuns());
    long end = System.nanoTime();
    if (load != null) {
      load.awaitSampleAfter(end, Duration.ofSeconds(2));
      results.environment(
          "machine CPU load during the run (mean / max)",
          ReportWriter.pct(load.mean(start, end)) + " / " + ReportWriter.pct(load.max(start, end)));
    }
    results.environment("finished", Instant.now());
    return results;
  }

  private static List<ProcedureResult> procedure(BenchContext ctx, Plan plan, String proc)
      throws InterruptedException {
    return switch (proc) {
      case "Q05/bloom" ->
          List.of(BloomVariants.run(ctx, plan.bloomDays(), ctx.runner().options().warmRuns()));
      case "Q11/cost" -> List.of(SqlIdsCost.run(ctx));
      case "Q14" -> PercentileAccuracy.run(ctx, plan.q14());
      case "Q15" -> List.of(DedupReinsert.run(ctx, plan.q15Transactions(), plan.q15Repeats()));
      case "Q16" -> List.of(LateArrival.run(ctx, plan.q16Rows(), 50_000));
      case "Q17" -> List.of(ConcurrentLoad.run(ctx, plan.q17()));
      case "Q18" -> List.of(RetentionOps.run(ctx, plan.q18()));
      case "Q19" -> List.of(MigrationRehearsal.run(ctx));
      case "Q20" -> List.of(CompressionComparison.run(ctx, plan.q20()));
      default -> throw new IllegalArgumentException("Unknown procedure " + proc);
    };
  }

  private static void environment(BenchContext ctx, MachineLoad load, BenchResults results) {
    results.environment("started", results.started());
    results.environment("run id", ctx.runner().options().runId());
    try {
      results.environment(
          "ClickHouse version", ctx.client(Account.READER).scalar("SELECT version()"));
    } catch (RuntimeException e) {
      results.environment("ClickHouse version", "unknown: " + e.getMessage());
    }
    results.environment("server", ctx.runner().server().describe());
    results.environment(
        "schema",
        "database "
            + ctx.schema().database()
            + ", table prefix '"
            + ctx.schema().tablePrefix()
            + "'");
    results.environment(
        "data range",
        ctx.shared().get("start_minute")
            + " .. "
            + ctx.shared().get("end_minute")
            + " ("
            + ctx.shared().get("days")
            + " days), busiest app "
            + ctx.shared().get("app"));
    results.environment(
        "runner",
        ctx.runner().options().warmRuns()
            + " warm runs per timing variant; cold: "
            + ctx.runner().options().cold().describe());
    if (load != null) {
      results.environment(
          "machine",
          load.processors()
              + " logical processors, "
              + String.format(
                  Locale.ROOT, "%.1f GiB", load.totalMemoryBytes() / (double) (1L << 30))
              + " RAM, "
              + System.getProperty("os.name")
              + " "
              + System.getProperty("os.version")
              + ", Java "
              + Runtime.version());
      results.environment("machine CPU load at start", ReportWriter.pct(load.now()));
    }
  }

  static ProcedureResult.Table dataTable(String body) {
    List<List<String>> rows = new ArrayList<>();
    for (List<String> r : Tsv.rows(body)) {
      long n = Long.parseLong(r.get(1));
      long un = Long.parseLong(r.get(2));
      long co = Long.parseLong(r.get(3));
      rows.add(
          List.of(
              r.get(0),
              r.get(1),
              ReportWriter.num(co / 1048576.0, 1),
              co == 0 ? "-" : ReportWriter.num(un / (double) co, 2),
              n == 0 ? "-" : ReportWriter.num(co / (double) n, 1),
              r.get(6)));
    }
    return new ProcedureResult.Table(
        "Tables of the loaded data (system.parts, active parts)",
        List.of("table", "rows", "compressed MiB", "ratio", "B/row", "parts"),
        rows);
  }
}
