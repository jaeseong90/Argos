/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.ops.migration.RollupSelects;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.rollup.RebuildResult.Level;
import io.argos.server.ops.rollup.RebuildResult.TableResult;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.Digest;
import io.argos.server.storage.clickhouse.RollupPartitions.Partition;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds the rollup partitions of one UTC day with the procedure of ADR-0007 C-59 (12 §9.1, D-14,
 * F-S22): for every 1m rollup the day partition, then for every 1h rollup the month partition of
 * that day, each one through its staging table {@code <t>_rebuild} and {@code REPLACE PARTITION}
 * ({@link RollupPartitions#replace}). The rollup SELECTs are the bundled files the materialized
 * views use ({@link RollupSelects}), so a rebuild writes what the views write. The unit is the
 * whole partition of every application (C-59 (c): there is no per-application rebuild).
 *
 * <p>Before and after each table the digest of the partition ({@link RollupPartitions#digest}) is
 * computed; equal digests mean the stored aggregates were already those of the source, byte for
 * byte. The result is returned and logged at INFO (one line per table and a summary).
 *
 * <p>Refusals (nothing is changed): a day after today; a day whose source rows may already have
 * expired ({@code day + retention <= now} for the raw source of a 1m table: {@code xlog-days},
 * {@code profile-days} or {@code raw-days}); a month whose 1m source may have expired ({@code first
 * day of the month + rollup-1m-days <= now}). Rebuilding such a partition would drop the expired
 * rows from the rollup.
 *
 * <p>The caller provides the statements of the migration account and makes sure that no other
 * rebuild runs at the same time (the advisory lock of {@code AdminDatabase}). Rows that the views
 * write into a partition while it is being rebuilt (today's day, the current month) can be missed
 * by the swap; the nightly consistency check finds and repairs them.
 */
public final class RollupRebuilder {

  /** Upper bound of one statement of a rebuild (the migrator profile allows 3600 s). */
  public static final Duration STATEMENT_TIMEOUT = Duration.ofMinutes(30);

  /** Suffix of the staging table (C-59 (a): {@code <t>_rebuild}). */
  public static final String STAGING_SUFFIX = "_rebuild";

  private static final Logger LOG = LoggerFactory.getLogger(RollupRebuilder.class);

  private final RollupSelects selects;
  private final SchemaParameters params;
  private final RollupPartitions.Statements<StatementException> sql;
  private final Clock clock;

  /**
   * @param selects the bundled rollup SELECTs
   * @param params database, prefix and retention of the server configuration
   * @param sql statements of the migration account
   * @param clock UTC clock (today, retention checks)
   */
  public RollupRebuilder(
      RollupSelects selects,
      SchemaParameters params,
      RollupPartitions.Statements<StatementException> sql,
      Clock clock) {
    this.selects = selects;
    this.params = params;
    this.sql = sql;
    this.clock = clock;
  }

  /**
   * The tables a rebuild of {@code day} covers, in rebuild order.
   *
   * @param minute 1m tables (day partition), sorted
   * @param hour 1h tables (month partition), sorted
   */
  public record Plan(LocalDate day, List<String> minute, List<String> hour) {

    public Plan {
      minute = List.copyOf(minute);
      hour = List.copyOf(hour);
    }

    /** Every table, 1m first. */
    public List<String> tables() {
      List<String> all = new ArrayList<>(minute);
      all.addAll(hour);
      return all;
    }
  }

  /**
   * Maps logical rollup names to their physical tables ({@code settings.rollup_alias}, 13 §13.2); a
   * name without an alias is its own table.
   */
  public static Set<String> resolve(Collection<String> names, Map<String, String> aliases) {
    Set<String> physical = new LinkedHashSet<>();
    for (String name : names) {
      physical.add(aliases.getOrDefault(name, name));
    }
    return physical;
  }

  /**
   * Rebuilds {@code day}: the 1m tables of {@code tables} (every existing 1m rollup when empty) and
   * the 1h tables of the same month (the requested 1h tables and the 1h cascades of the requested
   * 1m tables; every existing 1h rollup when empty), C-59 (b).
   *
   * @param tables physical rollup table names without database and prefix, or empty for all
   * @throws IllegalArgumentException for a day after today or a name that is not an existing rollup
   *     table (nothing is changed)
   * @throws RollupRebuildException when a source may have expired (nothing is changed) or a
   *     ClickHouse statement failed (tables before the failed one keep their new partitions, the
   *     failed table keeps its old partition)
   */
  public RebuildResult rebuildDay(LocalDate day, Set<String> tables) throws RollupRebuildException {
    List<String> existing;
    try {
      existing = RollupPartitions.tables(sql, params.database(), params.tablePrefix());
    } catch (StatementException e) {
      throw new RollupRebuildException("Cannot list the ClickHouse tables: " + e.getMessage(), e);
    }
    Plan plan = plan(day, tables, existing);
    checkRetention(plan);
    long start = System.nanoTime();
    List<TableResult> results = new ArrayList<>();
    for (String table : plan.minute()) {
      results.add(rebuild(table, Level.MINUTE, Partition.day(day)));
    }
    YearMonth month = YearMonth.from(day);
    for (String table : plan.hour()) {
      results.add(rebuild(table, Level.HOUR, Partition.month(month)));
    }
    RebuildResult result =
        new RebuildResult(day, results, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    LOG.info(
        "Rollup rebuild of {} finished in {} ms: {} tables, {} identical, changed: {}",
        day,
        result.durationMs(),
        results.size(),
        results.size() - result.changedTables().size(),
        result.changedTables().isEmpty() ? "none" : String.join(", ", result.changedTables()));
    return result;
  }

  /**
   * The tables of a rebuild of {@code day} (no database access; {@code existing} is the list of
   * tables in ClickHouse without prefix).
   *
   * @throws IllegalArgumentException for a day after today or an unknown or missing table
   */
  public Plan plan(LocalDate day, Set<String> requested, Collection<String> existing) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (day.isAfter(today)) {
      throw new IllegalArgumentException(
          "The day " + day + " is after today (" + today + ", UTC): nothing to rebuild");
    }
    Set<String> files = selects.tables();
    Map<String, String> sources = new LinkedHashMap<>();
    for (String table : files) {
      if (existing.contains(table)) {
        sources.put(table, selects.sourceTable(table).orElse(""));
      }
    }
    for (String table : requested) {
      if (!files.contains(table)) {
        throw new IllegalArgumentException(
            "Unknown rollup table " + table + "; rollup tables: " + String.join(", ", files));
      }
      if (!sources.containsKey(table)) {
        throw new IllegalArgumentException(
            "The rollup table "
                + table
                + " does not exist in ClickHouse ("
                + params.database()
                + ")");
      }
    }
    Set<String> minute = new TreeSet<>();
    Set<String> hour = new TreeSet<>();
    for (Map.Entry<String, String> e : sources.entrySet()) {
      String table = e.getKey();
      // A rollup whose source is itself a rollup is a 1h cascade.
      boolean hourly = files.contains(e.getValue());
      if (requested.isEmpty() || requested.contains(table)) {
        (hourly ? hour : minute).add(table);
      }
    }
    // C-59 (b): a rebuilt 1m day brings the 1h month of its cascades along.
    for (Map.Entry<String, String> e : sources.entrySet()) {
      if (minute.contains(e.getValue())) {
        hour.add(e.getKey());
      }
    }
    return new Plan(day, new ArrayList<>(minute), new ArrayList<>(hour));
  }

  /** Refuses partitions whose source rows may already have expired (see the class comment). */
  void checkRetention(Plan plan) throws RollupRebuildException {
    Instant now = clock.instant();
    List<String> refused = new ArrayList<>();
    for (String table : plan.minute()) {
      String source = selects.sourceTable(table).orElse("");
      String key = rawRetentionKey(source);
      int days = params.retention(key);
      if (!plan.day().plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(now)) {
        refused.add(table + " (source " + source + ", retention." + key + " " + days + " days)");
      }
    }
    if (!plan.hour().isEmpty()) {
      int days = params.retention("rollup-1m-days");
      LocalDate monthStart = plan.day().withDayOfMonth(1);
      if (!monthStart.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(now)) {
        for (String table : plan.hour()) {
          refused.add(
              table
                  + " (month "
                  + YearMonth.from(plan.day())
                  + ", source "
                  + selects.sourceTable(table).orElse("")
                  + ", retention.rollup-1m-days "
                  + days
                  + " days)");
        }
      }
    }
    if (!refused.isEmpty()) {
      throw new RollupRebuildException(
          "Refused: the source of "
              + String.join(", ", refused)
              + " may have expired for "
              + plan.day()
              + "; a rebuild would drop the expired rows from the rollup");
    }
  }

  /** The retention key of a raw table (13 §8). */
  static String rawRetentionKey(String rawTable) {
    return switch (rawTable) {
      case "xlog" -> "xlog-days";
      case "profile_step" -> "profile-days";
      default -> "raw-days";
    };
  }

  private TableResult rebuild(String table, Level level, Partition partition)
      throws RollupRebuildException {
    long start = System.nanoTime();
    String physical = params.tablePrefix() + table;
    String target = params.table(table);
    try {
      Digest before = RollupPartitions.digest(sql, params.database(), physical, partition);
      RollupPartitions.Replacement replacement =
          RollupPartitions.replace(
              sql,
              target,
              params.table(table + STAGING_SUFFIX),
              partition,
              selects.select(table, params, "AND (" + partition.sourcePredicate() + ")"),
              RollupPartitions.EmptyStaging.DROP);
      Digest after = RollupPartitions.digest(sql, params.database(), physical, partition);
      TableResult result =
          new TableResult(
              table,
              level,
              partition.key(),
              before,
              after,
              replacement.stagingRows(),
              replacement.dropped(),
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      LOG.info(
          "Rollup rebuild {} partition {}: {} rows, digest before {} keys {}, after {} keys {}: {}"
              + "{}, {} ms",
          table,
          partition.key(),
          replacement.stagingRows(),
          before.keys(),
          before.hash(),
          after.keys(),
          after.hash(),
          result.identical() ? "identical" : "changed",
          replacement.dropped() ? " (partition dropped: no source rows)" : "",
          result.durationMs());
      return result;
    } catch (StatementException | IllegalStateException e) {
      throw new RollupRebuildException(
          "Rollup rebuild of "
              + table
              + " partition "
              + partition.key()
              + " failed: "
              + e.getMessage(),
          e);
    }
  }
}
