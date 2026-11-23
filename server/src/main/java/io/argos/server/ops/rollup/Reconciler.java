/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.rollup.ConsistencyChecks.D12Spec;
import io.argos.server.ops.rollup.ConsistencyChecks.DayTotals;
import io.argos.server.ops.rollup.ConsistencyChecks.Mismatch;
import io.argos.server.ops.rollup.ConsistencyChecks.Spec;
import io.argos.server.ops.rollup.ReconcileReport.D12Check;
import io.argos.server.ops.rollup.ReconcileReport.TableCheck;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One consistency check of a UTC day (F-S22, D-14, 12 §9.1): runs every comparison of {@link
 * ConsistencyChecks} whose tables exist and whose sources still hold the day, rebuilds the
 * mismatching rollup tables of the day with {@link RollupRebuilder} (whole partitions, C-59) and
 * checks them again; when rows that arrived late race the rebuild, a second rebuild follows (at
 * most {@link #MAX_ATTEMPTS}). D-12 differences are recorded only. Every difference is logged at
 * WARN (one line per application, check and table: ids, table names and minutes only), the run at
 * INFO.
 *
 * <p>The caller holds the rollup lock ({@code AdminDatabase.ROLLUP_LOCK_KEY}) and has checked that
 * the ClickHouse schema is applied; the statements are those of the migration account.
 */
public final class Reconciler {

  /** Most rebuilds of one run (the first and one more for rows that raced it). */
  public static final int MAX_ATTEMPTS = 2;

  private static final Logger LOG = LoggerFactory.getLogger(Reconciler.class);

  private final RollupPartitions.Statements<StatementException> sql;
  private final SchemaParameters params;
  private final Map<String, String> aliases;
  private final RollupRebuilder rebuilder;
  private final Clock clock;

  /**
   * @param sql statements of the migration account
   * @param params database, prefix and retention of the server configuration
   * @param aliases {@code settings.rollup_alias} (logical to physical rollup names)
   * @param rebuilder the rebuild of a day (same statements)
   * @param clock UTC clock (retention of the sources)
   */
  public Reconciler(
      RollupPartitions.Statements<StatementException> sql,
      SchemaParameters params,
      Map<String, String> aliases,
      RollupRebuilder rebuilder,
      Clock clock) {
    this.sql = sql;
    this.params = params;
    this.aliases = Map.copyOf(aliases);
    this.rebuilder = rebuilder;
    this.clock = clock;
  }

  /**
   * Checks {@code day} and, when {@code rebuild}, repairs it.
   *
   * @param trigger {@link ReconcileReport#SCHEDULED} or {@link ReconcileReport#COMMAND}
   * @throws IllegalArgumentException for a day after today (UTC)
   * @throws StatementException when a comparison query fails (a failed rebuild is part of the
   *     report instead)
   */
  public ReconcileReport run(LocalDate day, String trigger, boolean rebuild)
      throws StatementException {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (day.isAfter(today)) {
      throw new IllegalArgumentException(
          "The day " + day + " is after today (" + today + ", UTC): nothing to check");
    }
    long start = System.nanoTime();
    List<String> existing = RollupPartitions.tables(sql, params.database(), params.tablePrefix());
    List<String> skipped = new ArrayList<>();
    List<Spec> specs = new ArrayList<>();
    for (Spec spec : ConsistencyChecks.SPECS) {
      String table = ConsistencyChecks.physical(spec.table(), aliases);
      String source = ConsistencyChecks.physical(spec.source(), aliases);
      if (!existing.contains(table) || !existing.contains(source)) {
        skipped.add(table + ": table missing");
      } else if (!covered(day, spec.retentionKey())) {
        skipped.add(table + ": outside retention." + spec.retentionKey());
      } else {
        specs.add(spec);
      }
    }
    Map<Spec, Map<Integer, Mismatch>> first = new LinkedHashMap<>();
    for (Spec spec : specs) {
      first.put(spec, check(spec, day));
    }
    List<D12Check> d12 = new ArrayList<>();
    boolean d12Covered = covered(day, "xlog-days") && covered(day, "raw-days");
    for (D12Spec spec : ConsistencyChecks.D12_SPECS) {
      if (!existing.contains(spec.rawTable()) || !existing.contains("xlog")) {
        skipped.add(spec.rawTable() + ": table missing");
      } else if (!d12Covered) {
        skipped.add(spec.rawTable() + ": outside retention (D-12)");
      } else {
        d12.add(d12(spec, day));
      }
    }

    Map<Spec, Map<Integer, Mismatch>> last = new LinkedHashMap<>(first);
    Map<String, Integer> attempts = new HashMap<>();
    List<RebuildResult> rebuilds = new ArrayList<>();
    String failure = null;
    Set<String> failedTables = new LinkedHashSet<>();
    if (rebuild) {
      Set<String> pending = mismatching(last);
      while (!pending.isEmpty() && rebuilds.size() < MAX_ATTEMPTS) {
        RebuildResult result;
        try {
          result = rebuilder.rebuildDay(day, pending);
        } catch (RollupRebuildException | IllegalArgumentException e) {
          failure = e.getMessage();
          failedTables.addAll(pending);
          LOG.warn(
              "Consistency check of {}: the rebuild of {} failed: {}",
              day,
              String.join(", ", pending),
              e.getMessage());
          break;
        }
        rebuilds.add(result);
        Set<String> rebuilt = new LinkedHashSet<>();
        for (RebuildResult.TableResult t : result.tables()) {
          rebuilt.add(t.table());
          attempts.merge(t.table(), 1, Integer::sum);
        }
        for (Spec spec : specs) {
          if (rebuilt.contains(ConsistencyChecks.physical(spec.table(), aliases))) {
            last.put(spec, check(spec, day));
          }
        }
        pending = mismatching(last);
      }
    }

    List<TableCheck> tables = new ArrayList<>();
    for (Spec spec : specs) {
      String table = ConsistencyChecks.physical(spec.table(), aliases);
      tables.add(
          new TableCheck(
              spec,
              table,
              first.get(spec),
              last.get(spec),
              attempts.getOrDefault(table, 0),
              failedTables.contains(table)));
    }
    ReconcileReport report =
        new ReconcileReport(
            day,
            trigger,
            tables,
            d12,
            rebuilds,
            failure,
            rebuild,
            skipped,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    log(report);
    return report;
  }

  private boolean covered(LocalDate day, String retentionKey) {
    Instant now = clock.instant();
    return day.plusDays(params.retention(retentionKey))
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .isAfter(now);
  }

  private Set<String> mismatching(Map<Spec, Map<Integer, Mismatch>> results) {
    Set<String> tables = new LinkedHashSet<>();
    for (Map.Entry<Spec, Map<Integer, Mismatch>> e : results.entrySet()) {
      if (!e.getValue().isEmpty()) {
        tables.add(ConsistencyChecks.physical(e.getKey().table(), aliases));
      }
    }
    return tables;
  }

  private Map<Integer, Mismatch> check(Spec spec, LocalDate day) throws StatementException {
    String query =
        ConsistencyChecks.mismatchQuery(
            spec,
            ConsistencyChecks.physical(spec.table(), aliases),
            ConsistencyChecks.physical(spec.source(), aliases),
            params,
            day);
    return byApp(sql.queryColumn(query));
  }

  private D12Check d12(D12Spec spec, LocalDate day) throws StatementException {
    Map<Integer, Mismatch> minutes =
        byApp(sql.queryColumn(ConsistencyChecks.d12MinuteQuery(spec, params, day)));
    Map<Integer, DayTotals> days = new LinkedHashMap<>();
    for (String line : sql.queryColumn(ConsistencyChecks.d12DayQuery(spec, params, day))) {
      DayTotals totals = ConsistencyChecks.parseDayTotals(line);
      days.put(totals.appId(), totals);
    }
    return new D12Check(spec, minutes, days);
  }

  private static Map<Integer, Mismatch> byApp(Collection<String> lines) {
    Map<Integer, Mismatch> out = new LinkedHashMap<>();
    for (String line : lines) {
      Mismatch m = ConsistencyChecks.parseMismatch(line);
      if (out.size() < ConsistencyChecks.MAX_APPS) {
        out.put(m.appId(), m);
      }
    }
    return out;
  }

  private static void log(ReconcileReport report) {
    for (TableCheck t : report.tables()) {
      Set<Integer> apps = new LinkedHashSet<>(t.first().keySet());
      apps.addAll(t.last().keySet());
      for (int app : apps) {
        Mismatch m = t.first().get(app);
        LOG.warn(
            "Consistency check of {}: app {} {} {}: {} mismatching minutes (first: {}), {} left"
                + " after {} rebuilds: {}",
            report.day(),
            app,
            t.spec().check(),
            t.table(),
            t.firstMinutes(app),
            m == null ? "-" : String.join(",", m.first()),
            t.lastMinutes(app),
            t.attempts(),
            t.outcome(app, report.rebuildEnabled()));
      }
    }
    for (D12Check c : report.d12()) {
      for (int app : c.mismatchingApps()) {
        Mismatch m = c.minutes().get(app);
        DayTotals totals = c.days().get(app);
        LOG.warn(
            "Consistency check of {}: app {} d12 {}: {} minutes beyond the bound (first: {}), day"
                + " xlog {} vs statistics {} (bound {}); recorded only, suspected collection loss",
            report.day(),
            app,
            c.spec().rawTable(),
            m == null ? 0 : m.buckets(),
            m == null ? "-" : String.join(",", m.first()),
            totals == null ? 0 : totals.xlog(),
            totals == null ? 0 : totals.statistics(),
            totals == null ? 0 : totals.bound());
      }
    }
    LOG.info(
        "Consistency check of {} ({}) finished in {} ms: {} comparisons, result {}, mismatching: {},"
            + " rebuilds: {}, still mismatching: {}{}",
        report.day(),
        report.trigger(),
        report.durationMs(),
        report.tables().size() + report.d12().size(),
        report.result(),
        report.mismatchingTables().isEmpty()
            ? "none"
            : String.join(", ", report.mismatchingTables()),
        report.rebuilds().size(),
        report.remainingTables().isEmpty() ? "none" : String.join(", ", report.remainingTables()),
        report.skipped().isEmpty() ? "" : ", skipped: " + String.join("; ", report.skipped()));
  }
}
