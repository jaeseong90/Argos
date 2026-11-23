/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.ops.rollup.ConsistencyChecks.DayTotals;
import io.argos.server.ops.rollup.ConsistencyChecks.Mismatch;
import io.argos.server.ops.rollup.ConsistencyChecks.Spec;
import io.argos.server.ops.rollup.RebuildResult.TableResult;
import io.argos.server.storage.postgres.ops.ReconcileResultRepository.Row;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The result of one consistency check of a day ({@link Reconciler#run}): every comparison with the
 * applications that differed on the first check and after the last rebuild, the D-12 comparisons,
 * and the rebuilds. {@link #rows} turns it into the {@code reconcile_results} rows, {@link #json}
 * into the {@code --json} document of {@code argos-admin consistency check}.
 *
 * @param day the checked UTC day
 * @param trigger {@link #SCHEDULED} or {@link #COMMAND}
 * @param tables the rollup comparisons that ran
 * @param d12 the D-12 comparisons that ran
 * @param rebuilds the rebuilds, in order (at most {@link Reconciler#MAX_ATTEMPTS})
 * @param rebuildFailure the message of a failed rebuild, or null
 * @param rebuildEnabled the run rebuilds mismatching tables
 * @param skipped comparisons left out because the day is outside a source's retention or a table
 *     does not exist ({@code <table>: <reason>})
 * @param durationMs wall time of the run
 */
public record ReconcileReport(
    LocalDate day,
    String trigger,
    List<TableCheck> tables,
    List<D12Check> d12,
    List<RebuildResult> rebuilds,
    @Nullable String rebuildFailure,
    boolean rebuildEnabled,
    List<String> skipped,
    long durationMs) {

  /** Trigger of the nightly job. */
  public static final String SCHEDULED = "scheduled";

  /** Trigger of {@code argos-admin consistency check}. */
  public static final String COMMAND = "command";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public ReconcileReport {
    tables = List.copyOf(tables);
    d12 = List.copyOf(d12);
    rebuilds = List.copyOf(rebuilds);
    skipped = List.copyOf(skipped);
  }

  /**
   * One rollup comparison.
   *
   * @param spec the comparison
   * @param table the physical rollup table
   * @param first applications that differed on the first check
   * @param last applications that differed on the last check (= {@code first} without a rebuild)
   * @param attempts rebuilds that covered this table
   * @param rebuildFailed a rebuild that should have covered this table failed
   */
  public record TableCheck(
      Spec spec,
      String table,
      Map<Integer, Mismatch> first,
      Map<Integer, Mismatch> last,
      int attempts,
      boolean rebuildFailed) {

    public TableCheck {
      first = new TreeMap<>(first);
      last = new TreeMap<>(last);
    }

    /** Mismatching minutes of an application on the first check (an hour counts 60). */
    public int firstMinutes(int appId) {
      Mismatch m = first.get(appId);
      return m == null ? 0 : m.buckets() * spec.bucket().minutes();
    }

    /** Mismatching minutes of an application on the last check (an hour counts 60). */
    public int lastMinutes(int appId) {
      Mismatch m = last.get(appId);
      return m == null ? 0 : m.buckets() * spec.bucket().minutes();
    }

    /** The outcome for one application: {@code clean}, {@code repaired}, ... (V005). */
    public String outcome(int appId, boolean rebuildEnabled) {
      boolean before = first.containsKey(appId);
      boolean after = last.containsKey(appId);
      if (!before && !after) {
        return "clean";
      }
      if (!rebuildEnabled) {
        return "not_rebuilt";
      }
      if (rebuildFailed && after) {
        return "failed";
      }
      if (attempts == 0) {
        return rebuildFailed ? "failed" : "not_rebuilt";
      }
      return after ? "mismatch" : "repaired";
    }
  }

  /**
   * One D-12 comparison (recorded only).
   *
   * @param spec the comparison
   * @param minutes applications with minutes beyond the per-minute bound
   * @param days the day totals of every application with data
   */
  public record D12Check(
      ConsistencyChecks.D12Spec spec,
      Map<Integer, Mismatch> minutes,
      Map<Integer, DayTotals> days) {

    public D12Check {
      minutes = new TreeMap<>(minutes);
      days = new TreeMap<>(days);
    }

    /** Applications with a D-12 difference (a minute beyond its bound or the day beyond C-57). */
    public List<Integer> mismatchingApps() {
      TreeSet<Integer> apps = new TreeSet<>(minutes.keySet());
      for (DayTotals t : days.values()) {
        if (!t.withinBound()) {
          apps.add(t.appId());
        }
      }
      return new ArrayList<>(apps);
    }
  }

  /** Mismatching D-03 minutes of an application on the first check (the gauge, 12 §9.1). */
  public int d03Minutes(int appId) {
    int minutes = 0;
    for (TableCheck t : tables) {
      if (t.spec().check().equals(ConsistencyChecks.D03)) {
        minutes += t.firstMinutes(appId);
      }
    }
    return minutes;
  }

  /** Applications with a difference in a rollup comparison on the first or the last check. */
  public List<Integer> mismatchingApps() {
    TreeSet<Integer> apps = new TreeSet<>();
    for (TableCheck t : tables) {
      apps.addAll(t.first().keySet());
      apps.addAll(t.last().keySet());
    }
    return new ArrayList<>(apps);
  }

  /** Rollup tables that differed on the first check. */
  public List<String> mismatchingTables() {
    return tables.stream().filter(t -> !t.first().isEmpty()).map(TableCheck::table).toList();
  }

  /** Rollup tables that still differ after the last check. */
  public List<String> remainingTables() {
    return tables.stream().filter(t -> !t.last().isEmpty()).map(TableCheck::table).toList();
  }

  /** The overall result: {@code clean}, {@code repaired}, {@code mismatch}, ... (V005). */
  public String result() {
    if (mismatchingTables().isEmpty() && remainingTables().isEmpty()) {
      return "clean";
    }
    if (remainingTables().isEmpty()) {
      return rebuilds.isEmpty() ? "clean" : "repaired";
    }
    if (!rebuildEnabled) {
      return "not_rebuilt";
    }
    return rebuildFailure != null ? "failed" : "mismatch";
  }

  /** The {@code reconcile_results} rows: one per application, check and table, and the summary. */
  public List<Row> rows(Instant checkedAt) {
    List<Row> rows = new ArrayList<>();
    for (TableCheck t : tables) {
      TreeSet<Integer> apps = new TreeSet<>(t.first().keySet());
      apps.addAll(t.last().keySet());
      for (int app : apps) {
        ObjectNode detail = JSON.createObjectNode();
        detail.put("trigger", trigger);
        detail.put("unit", t.spec().bucket() == ConsistencyChecks.Bucket.HOUR ? "1h" : "1m");
        Mismatch first = t.first().get(app);
        ArrayNode buckets = detail.putArray("first_buckets");
        if (first != null) {
          first.first().forEach(buckets::add);
        }
        detail.put("remaining_minutes", t.lastMinutes(app));
        detail.put("attempts", t.attempts());
        digests(detail.putArray("rebuilds"), t.table());
        String outcome = t.outcome(app, rebuildEnabled);
        rows.add(
            new Row(
                day,
                app,
                t.spec().check(),
                t.table(),
                t.firstMinutes(app),
                t.attempts() > 0,
                outcome,
                checkedAt,
                JSON.writeValueAsString(detail)));
      }
    }
    for (D12Check c : d12) {
      for (int app : c.mismatchingApps()) {
        Mismatch m = c.minutes().get(app);
        DayTotals totals = c.days().get(app);
        ObjectNode detail = JSON.createObjectNode();
        detail.put("trigger", trigger);
        detail.put("stream", c.spec().name());
        ArrayNode buckets = detail.putArray("first_buckets");
        if (m != null) {
          m.first().forEach(buckets::add);
        }
        if (totals != null) {
          detail.put("xlog_sum", totals.xlog());
          detail.put("stat_sum", totals.statistics());
          detail.put("day_bound", totals.bound());
          detail.put("day_within_bound", totals.withinBound());
        }
        rows.add(
            new Row(
                day,
                app,
                ConsistencyChecks.D12,
                c.spec().rawTable(),
                m == null ? 0 : m.buckets(),
                false,
                "record_only",
                checkedAt,
                JSON.writeValueAsString(detail)));
      }
    }
    rows.add(
        new Row(
            day,
            null,
            "summary",
            "*",
            totalFirstMinutes(),
            !rebuilds.isEmpty(),
            result(),
            checkedAt,
            JSON.writeValueAsString(summary())));
    return rows;
  }

  private int totalFirstMinutes() {
    long sum = 0;
    for (TableCheck t : tables) {
      for (int app : t.first().keySet()) {
        sum += t.firstMinutes(app);
      }
    }
    return (int) Math.min(Integer.MAX_VALUE, sum);
  }

  private void digests(ArrayNode out, String table) {
    int attempt = 0;
    for (RebuildResult r : rebuilds) {
      attempt++;
      for (TableResult t : r.tables()) {
        if (t.table().equals(table)) {
          ObjectNode node = out.addObject();
          node.put("attempt", attempt);
          node.put("partition", t.partition());
          node.put("identical", t.identical());
          node.put("before_keys", t.before().keys());
          node.put("before_hash", t.before().hash());
          node.put("after_keys", t.after().keys());
          node.put("after_hash", t.after().hash());
        }
      }
    }
  }

  private ObjectNode summary() {
    ObjectNode node = JSON.createObjectNode();
    node.put("trigger", trigger);
    node.put("tables_checked", tables.size());
    node.put("d12_checked", d12.size());
    node.put("apps_mismatching", mismatchingApps().size());
    ArrayNode mismatching = node.putArray("mismatching_tables");
    mismatchingTables().forEach(mismatching::add);
    ArrayNode remaining = node.putArray("remaining_tables");
    remainingTables().forEach(remaining::add);
    node.put("attempts", rebuilds.size());
    if (rebuildFailure != null) {
      node.put("rebuild_failure", rebuildFailure);
    }
    TreeSet<Integer> d12Apps = new TreeSet<>();
    d12.forEach(c -> d12Apps.addAll(c.mismatchingApps()));
    node.put("d12_apps_mismatching", d12Apps.size());
    ArrayNode skippedNode = node.putArray("skipped");
    skipped.forEach(skippedNode::add);
    node.put("duration_ms", durationMs);
    return node;
  }

  /** The {@code --json} document of {@code argos-admin consistency check}. */
  public String json() {
    ObjectNode root = JSON.createObjectNode();
    root.put("command", "consistency check");
    root.put("day", day.toString());
    root.put("result", result());
    root.put("rebuild", rebuildEnabled);
    root.put("attempts", rebuilds.size());
    if (rebuildFailure != null) {
      root.put("rebuild_failure", rebuildFailure);
    }
    root.put("duration_ms", durationMs);
    ArrayNode checks = root.putArray("checks");
    Instant now = Instant.EPOCH;
    for (Row row : rows(now)) {
      if (row.appId() == null) {
        continue;
      }
      ObjectNode node = checks.addObject();
      node.put("app_id", row.appId());
      node.put("check", row.checkName());
      node.put("table", row.tableName());
      node.put("mismatch_minutes", row.mismatchMinutes());
      node.put("rebuilt", row.rebuilt());
      node.put("result", row.rebuildResult());
      node.set("detail", JSON.readTree(row.detailJson()));
    }
    ArrayNode checked = root.putArray("tables_checked");
    tables.forEach(t -> checked.add(t.table()));
    ArrayNode skippedNode = root.putArray("skipped");
    skipped.forEach(skippedNode::add);
    return JSON.writeValueAsString(root);
  }

  /** Per application: D-03 minutes (for the gauge). */
  public Map<Integer, Integer> d03MinutesByApp() {
    Map<Integer, Integer> out = new LinkedHashMap<>();
    for (int app : mismatchingApps()) {
      int minutes = d03Minutes(app);
      if (minutes > 0) {
        out.put(app, minutes);
      }
    }
    return out;
  }
}
