/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import io.argos.storage.queries.BenchContext;
import io.argos.storage.queries.Measurement;
import io.argos.storage.queries.Parameters;
import io.argos.storage.queries.ProcedureResult;
import io.argos.storage.queries.ProcedureResult.Table;
import io.argos.storage.queries.QueryFile;
import io.argos.storage.queries.Stats;
import io.argos.storage.queries.Tsv;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Q18 TTL, DROP PARTITION and deletion mutation costs (13 §8, §9, T-ST-054), on copies of xlog so
 * that the loaded data stays intact. Times are measured until the effect is visible: mutations
 * until system.mutations.is_done, the partition drop until its outdated parts are gone from
 * system.parts (disk space back), MATERIALIZE TTL until done.
 *
 * <p>The two user_id deletion forms (13 §9 {@code DELETE WHERE toDate(ts) = d ...} and {@code
 * DELETE IN PARTITION d WHERE ...}) are compared under control: two copies with the same content of
 * days d - 1 and d, the same subject deleted with one form on each copy, the order alternating per
 * repetition, merges settled before each mutation, and per run the subject rows, the parts of the
 * copy, parts_to_do first seen, the parts rewritten and the machine CPU load.
 */
public final class RetentionOps {

  private static final String COPY_A = "q18_xlog_a";
  private static final String COPY_B = "q18_xlog_b";
  private static final String TTL_COPY = "q18_ttl";
  private static final String WHERE = "mutation_where";
  private static final String IN_PARTITION = "mutation_in_partition";

  private RetentionOps() {}

  /**
   * Options.
   *
   * @param maxWait how long to wait for a mutation or the disk reclaim
   * @param repeats repetitions of the form comparison (each with its own subject)
   * @param copyHours hours of each day around the midnight between d - 1 and d copied (24: both
   *     days completely; fewer for a disk budget)
   * @param settle longest wait for the merges of a copy to settle before a mutation
   */
  public record Options(Duration maxWait, int repeats, int copyHours, Duration settle) {

    /** Bench values: 10 minutes, 2 repetitions, both whole days, 2 minutes. */
    public static Options bench() {
      return new Options(Duration.ofMinutes(10), 2, 24, Duration.ofMinutes(2));
    }
  }

  /**
   * Runs Q18.
   *
   * @param ctx context
   * @param o options
   */
  public static ProcedureResult run(BenchContext ctx, Options o) {
    QueryFile f = ctx.file("Q18");
    ProcedureResult r = new ProcedureResult("Q18", f.title(), f.criterion());
    LocalDate day = ctx.date("full_day");
    LocalDate oldest = ctx.date("first_day");
    String database = ctx.schema().database();
    String prefix = ctx.schema().tablePrefix();
    Instant midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant from = midnight.minus(Duration.ofHours(Math.min(24, o.copyHours())));
    Instant to = midnight.plus(Duration.ofHours(Math.min(24, o.copyHours())));
    Parameters base =
        ctx.runner()
            .baseParameters()
            .with(
                Map.of(
                    "day",
                    day.toString(),
                    "database",
                    database,
                    "from",
                    BenchContext.dateTime64(from),
                    "to",
                    BenchContext.dateTime64(to)));
    Map<String, Parameters> copies = new LinkedHashMap<>();
    copies.put(COPY_A, base.with("table", prefix + COPY_A));
    copies.put(COPY_B, base.with("table", prefix + COPY_B));
    List<List<String>> rows = new ArrayList<>();
    List<List<String>> mutations = new ArrayList<>();
    try {
      for (Map.Entry<String, Parameters> c : copies.entrySet()) {
        Map<String, String> copy = Map.of("copy", c.getKey());
        ctx.runner().stepOk(f, "copy_drop", c.getValue(), copy);
        ctx.runner().stepOk(f, "copy_create", c.getValue(), copy);
        Measurement fill = ctx.runner().stepOk(f, "copy_fill_range", c.getValue(), copy);
        long[] parts = parts(ctx, f, c.getValue());
        rows.add(
            List.of(
                "copy "
                    + c.getKey()
                    + " "
                    + BenchContext.dateTime(from)
                    + " .. "
                    + BenchContext.dateTime(to)
                    + " (INSERT SELECT)",
                ms(fill.wallMs()),
                parts[1] + " rows, " + parts[2] + " B, " + parts[0] + " parts",
                pct(ctx.runner().cpuLoad(fill))));
        r.metric("copy_rows", parts[1]).metric("copy_bytes", parts[2]);
      }

      List<List<String>> subjects =
          Tsv.rows(
              ctx.runner()
                  .stepOk(
                      f,
                      "copy_subjects",
                      copies.get(COPY_A).with("n", Integer.toString(o.repeats())),
                      Map.of("copy", COPY_A))
                  .body());
      Map<String, List<Double>> times = new LinkedHashMap<>();
      times.put(WHERE, new ArrayList<>());
      times.put(IN_PARTITION, new ArrayList<>());
      for (int rep = 0; rep < Math.min(o.repeats(), subjects.size()); rep++) {
        String[] order =
            rep % 2 == 0 ? new String[] {WHERE, IN_PARTITION} : new String[] {IN_PARTITION, WHERE};
        String[] targets = {COPY_A, COPY_B};
        for (int i = 0; i < 2; i++) {
          String form = order[i];
          String target = targets[i];
          Parameters subject =
              copies
                  .get(target)
                  .with(Map.of("app", subjects.get(rep).get(0), "uid", subjects.get(rep).get(1)));
          Map<String, String> copy = Map.of("copy", target);
          String settled = settle(ctx, f, subject, o.settle());
          long before = subjectRows(ctx, f, subject, copy);
          long[] partsBefore = parts(ctx, f, subject);
          Map<String, Long> detailBefore = detail(ctx, f, subject);
          long started = System.nanoTime();
          ctx.runner().stepOk(f, form, subject, copy);
          long[] wait = waitMutation(ctx, f, subject, o.maxWait());
          long ended = System.nanoTime();
          double elapsed = (ended - started) / 1e6;
          boolean done = wait[0] == 1;
          long after = subjectRows(ctx, f, subject, copy);
          Map<String, Long> detailAfter = detail(ctx, f, subject);
          int rewritten = 0;
          int changedOtherDay = 0;
          for (Map.Entry<String, Long> e : detailAfter.entrySet()) {
            Long previous = detailBefore.get(e.getKey());
            if (previous != null && !previous.equals(e.getValue())) {
              rewritten++;
              if (!e.getKey().startsWith(day.toString().replace("-", "") + "|")) {
                changedOtherDay++;
              }
            }
          }
          double cpu = ctx.runner().cpuLoad(started, ended);
          if (done) {
            times.get(form).add(elapsed);
          }
          mutations.add(
              List.of(
                  Integer.toString(rep + 1),
                  Integer.toString(i + 1),
                  form,
                  target,
                  subjects.get(rep).get(0) + "/" + subjects.get(rep).get(1),
                  before + " -> " + after,
                  Long.toString(partsBefore[0]),
                  Long.toString(wait[1]),
                  rewritten
                      + (changedOtherDay == 0
                          ? ""
                          : " (" + changedOtherDay + " not on " + day + ")"),
                  done ? ms(elapsed) : "> " + ms(elapsed),
                  pct(cpu),
                  settled));
        }
      }
      for (Map.Entry<String, List<Double>> t : times.entrySet()) {
        List<Double> v = t.getValue();
        r.metric(t.getKey() + "_ms", v.isEmpty() ? Double.NaN : Stats.mean(v));
        r.metric(
            t.getKey() + "_min_ms",
            v.isEmpty() ? Double.NaN : v.stream().mapToDouble(d -> d).min().orElse(Double.NaN));
        r.metric(t.getKey() + "_max_ms", v.isEmpty() ? Double.NaN : Stats.max(v));
      }

      Parameters dropTarget = copies.get(COPY_A);
      Map<String, String> copyA = Map.of("copy", COPY_A);
      long[] beforeDrop = parts(ctx, f, dropTarget);
      long freeBefore = freeSpace(ctx, f, dropTarget);
      Measurement drop = ctx.runner().stepOk(f, "drop_partition", dropTarget, copyA);
      long started = System.nanoTime();
      long outdated;
      do {
        outdated =
            Long.parseLong(
                Tsv.rows(ctx.runner().stepOk(f, "outdated_parts", dropTarget, Map.of()).body())
                    .get(0)
                    .get(0));
        if (outdated > 0) {
          sleep(1000);
        }
      } while (outdated > 0 && System.nanoTime() - started < o.maxWait().toNanos());
      double reclaim = (System.nanoTime() - started) / 1e6;
      long freeAfter = freeSpace(ctx, f, dropTarget);
      rows.add(
          List.of(
              "DROP PARTITION " + day + " (" + COPY_A + ")",
              ms(drop.wallMs()),
              beforeDrop[2]
                  + " B in "
                  + beforeDrop[0]
                  + " parts of the copy; outdated parts removed after "
                  + (outdated == 0
                      ? ms(reclaim) + " ms"
                      : "> " + ms(reclaim) + " ms (still " + outdated + ")")
                  + "; disk free "
                  + freeBefore
                  + " -> "
                  + freeAfter
                  + " B",
              pct(ctx.runner().cpuLoad(drop))));
      r.metric("drop_partition_ms", drop.wallMs());
      r.metric("disk_reclaim_ms", outdated == 0 ? reclaim : Double.NaN);
      for (Map.Entry<String, Parameters> c : copies.entrySet()) {
        ctx.runner().stepOk(f, "copy_drop", c.getValue(), Map.of("copy", c.getKey()));
      }

      // TTL: a copy of the oldest day with a 1 day TTL, then MATERIALIZE TTL.
      Map<String, String> ttlCopy = Map.of("copy", TTL_COPY);
      Parameters ttl = base.with(Map.of("day", oldest.toString(), "table", prefix + TTL_COPY));
      ctx.runner().stepOk(f, "copy_drop", ttl, ttlCopy);
      ctx.runner().stepOk(f, "copy_create", ttl, ttlCopy);
      ctx.runner().stepOk(f, "copy_fill", ttl, ttlCopy);
      long[] ttlBefore = parts(ctx, f, ttl);
      Measurement modify = ctx.runner().stepOk(f, "ttl_modify", ttl, ttlCopy);
      long materializeStart = System.nanoTime();
      ctx.runner().stepOk(f, "ttl_materialize", ttl, ttlCopy);
      boolean done = waitMutation(ctx, f, ttl, o.maxWait())[0] == 1;
      long materializeEnd = System.nanoTime();
      double materialize = (materializeEnd - materializeStart) / 1e6;
      long[] ttlAfter = parts(ctx, f, ttl);
      rows.add(
          List.of(
              "MODIFY TTL 1 DAY (materialize_ttl_after_modify = 0)",
              ms(modify.wallMs()),
              "metadata only",
              pct(ctx.runner().cpuLoad(modify))));
      rows.add(
          List.of(
              "MATERIALIZE TTL (copy of " + oldest + ")",
              done ? ms(materialize) : "> " + ms(materialize),
              "rows "
                  + ttlBefore[1]
                  + " -> "
                  + ttlAfter[1]
                  + ", parts "
                  + ttlBefore[0]
                  + " -> "
                  + ttlAfter[0],
              pct(ctx.runner().cpuLoad(materializeStart, materializeEnd))));
      r.metric("ttl_materialize_ms", done ? materialize : Double.NaN);
      r.metric("ttl_materialize_rows", ttlBefore[1]);
      r.metric("ttl_rows_left", ttlAfter[1]);
      ctx.runner().stepOk(f, "copy_drop", ttl, ttlCopy);
    } finally {
      ctx.runner().step(f, "copy_drop", base, Map.of("copy", COPY_A));
      ctx.runner().step(f, "copy_drop", base, Map.of("copy", COPY_B));
      ctx.runner().step(f, "copy_drop", base, Map.of("copy", TTL_COPY));
    }
    return r.table(
            new Table(
                "TTL / DROP / copies", List.of("operation", "ms", "detail", "machine CPU"), rows))
        .table(
            new Table(
                "user_id deletion: the two forms on copies with the same content (same subject per"
                    + " repetition, order alternating)",
                List.of(
                    "rep",
                    "order",
                    "form",
                    "copy",
                    "subject app/user",
                    "subject rows on " + day + " before -> after",
                    "active parts of the copy",
                    "parts_to_do first seen",
                    "parts rewritten",
                    "ms",
                    "machine CPU",
                    "merges before"),
                mutations))
        .note(
            "Natural TTL deletion happens at the next TTL merge (merge_with_ttl_timeout = 4 h, 13"
                + " §8); MATERIALIZE TTL forces it. Copies: "
                + BenchContext.dateTime(from)
                + " .. "
                + BenchContext.dateTime(to)
                + " (days "
                + day.minusDays(1)
                + " and "
                + day
                + ").")
        .judge();
  }

  /**
   * Waits until the latest mutation of the table is done; returns {done 0/1, parts_to_do first
   * seen}.
   */
  private static long[] waitMutation(
      BenchContext ctx, QueryFile f, Parameters p, Duration maxWait) {
    long start = System.nanoTime();
    long firstPartsToDo = -1;
    while (System.nanoTime() - start < maxWait.toNanos()) {
      List<List<String>> m =
          Tsv.rows(ctx.runner().stepOk(f, "mutation_status", p, Map.of()).body());
      if (!m.isEmpty() && firstPartsToDo < 0) {
        firstPartsToDo = Long.parseLong(m.get(0).get(2));
      }
      if (!m.isEmpty() && m.get(0).get(1).equals("1")) {
        return new long[] {1, firstPartsToDo};
      }
      if (!m.isEmpty() && !m.get(0).get(3).isEmpty()) {
        throw new IllegalStateException("Mutation failed: " + m.get(0));
      }
      sleep(200);
    }
    return new long[] {0, firstPartsToDo};
  }

  /** Waits until the copy has no merge and the same part count twice; returns what it saw. */
  private static String settle(BenchContext ctx, QueryFile f, Parameters p, Duration max) {
    long start = System.nanoTime();
    long previous = -1;
    int polls = 0;
    while (true) {
      long merges =
          Long.parseLong(
              Tsv.rows(ctx.runner().stepOk(f, "merges_running", p, Map.of()).body()).get(0).get(0));
      long parts = parts(ctx, f, p)[0];
      polls++;
      if (merges == 0 && parts == previous) {
        return "settled after " + ms((System.nanoTime() - start) / 1e6) + " ms";
      }
      if (System.nanoTime() - start > max.toNanos()) {
        return "not settled after "
            + ms((System.nanoTime() - start) / 1e6)
            + " ms ("
            + merges
            + " merges)";
      }
      previous = merges == 0 ? parts : -1;
      sleep(polls < 3 ? 1000 : 3000);
    }
  }

  private static long subjectRows(
      BenchContext ctx, QueryFile f, Parameters p, Map<String, String> copy) {
    return Long.parseLong(
        Tsv.rows(ctx.runner().stepOk(f, "copy_rows", p, copy).body()).get(0).get(1));
  }

  /** Active parts by "partition_id|min|max|level" to rows. */
  private static Map<String, Long> detail(BenchContext ctx, QueryFile f, Parameters p) {
    Map<String, Long> out = new HashMap<>();
    for (List<String> row : Tsv.rows(ctx.runner().stepOk(f, "parts_detail", p, Map.of()).body())) {
      out.put(
          row.get(0) + "|" + row.get(1) + "|" + row.get(2) + "|" + row.get(3),
          Long.parseLong(row.get(4)));
    }
    return out;
  }

  private static long[] parts(BenchContext ctx, QueryFile f, Parameters p) {
    List<String> row = Tsv.rows(ctx.runner().stepOk(f, "table_parts", p, Map.of()).body()).get(0);
    return new long[] {Long.parseLong(row.get(0)), parseLong(row.get(1)), parseLong(row.get(2))};
  }

  private static long parseLong(String v) {
    return v.isEmpty() || v.equals("\\N") ? 0 : Long.parseLong(v);
  }

  private static long freeSpace(BenchContext ctx, QueryFile f, Parameters p) {
    return Long.parseLong(
        Tsv.rows(ctx.runner().stepOk(f, "disk_free", p, Map.of()).body()).get(0).get(0));
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String ms(double v) {
    return String.format(Locale.ROOT, "%.0f", v);
  }

  private static String pct(double v) {
    return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.0f %%", v * 100);
  }
}
