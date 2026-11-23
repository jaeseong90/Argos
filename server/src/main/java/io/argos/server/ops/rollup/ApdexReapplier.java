/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.RollupSelects;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.rollup.Outcome.Reason;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import io.argos.server.storage.postgres.ops.AdminDatabase;
import io.argos.server.storage.postgres.ops.AdminDatabase.AdminDatabaseException;
import io.argos.server.storage.postgres.ops.AdminDatabase.AppApdex;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code argos-admin apdex reapply} (D-17 "원본 보존 기간 안에서 재구축으로 소급", WO-27 PM question 2): re-stamps
 * the stored transactions of one application (optionally one service) on a range of UTC days with
 * the Apdex T that PostgreSQL holds now ({@code apps.apdex_t_ms} and {@code
 * service_apdex_overrides}, the first {@link AdminDatabase#MAX_OVERRIDES_PER_APP} by service id as
 * the ingest path reads them) and rebuilds the Apdex rollups of those days, so the Apdex counts of
 * the past use the new T. Nothing does this automatically: a T change applies to data received from
 * then on (forward only), and only an operator running this command changes the past.
 *
 * <p>For each day, in order: the rows whose {@code xlog.apdex_t_ms} differs from the current T are
 * counted and updated with one mutation of that day's partition ({@code ALTER TABLE xlog UPDATE
 * apdex_t_ms = ... IN PARTITION ... SETTINGS mutations_sync = 2}, migration account), then the day
 * is rebuilt ({@link RollupRebuilder}: {@code app_stat_1m}, {@code service_stat_1m}, {@code
 * instance_stat_1m} and their 1h month partitions; the views do not see mutations). A day is done
 * before the next starts, so a failure leaves earlier days complete; running the command again
 * repeats the rebuild of every day of the range (the mutation finds nothing left to change).
 *
 * <p>Refused (nothing changed): a range longer than {@link #MAX_DAYS} days or ending after today
 * (usage), a first day outside the raw retention ({@code argos.retention.xlog}: the rows may be
 * gone and a rebuild would drop them from the rollups), an unknown application.
 */
public final class ApdexReapplier {

  /** Longest range of one run. */
  public static final int MAX_DAYS = 400;

  /** The rollups that count Apdex from {@code xlog.apdex_t_ms} (13 §4.2, 4.3, 4.7). */
  public static final List<String> APDEX_ROLLUPS =
      List.of("app_stat_1m", "service_stat_1m", "instance_stat_1m");

  private static final Logger LOG = LoggerFactory.getLogger(ApdexReapplier.class);

  private final RollupPartitions.Statements<StatementException> sql;
  private final SchemaParameters params;
  private final RollupRebuilder rebuilder;
  private final Set<String> rollups;
  private final Clock clock;

  /**
   * @param sql statements of the migration account
   * @param params database, prefix and retention
   * @param rebuilder the rebuild of a day (same statements)
   * @param aliases {@code settings.rollup_alias}
   * @param clock UTC clock
   */
  public ApdexReapplier(
      RollupPartitions.Statements<StatementException> sql,
      SchemaParameters params,
      RollupRebuilder rebuilder,
      Map<String, String> aliases,
      Clock clock) {
    this.sql = sql;
    this.params = params;
    this.rebuilder = rebuilder;
    this.rollups = RollupRebuilder.resolve(APDEX_ROLLUPS, aliases);
    this.clock = clock;
  }

  /**
   * One day of a run.
   *
   * @param day the UTC day
   * @param rowsChanged transactions whose T was changed
   * @param rebuild the rebuild of the day
   */
  public record DayResult(LocalDate day, long rowsChanged, RebuildResult rebuild) {}

  /**
   * The result of a run.
   *
   * @param appId the application
   * @param serviceId the service (unsigned value as a signed long), or null for every service
   * @param from first day
   * @param to last day
   * @param appTMs the application's T now
   * @param overrides service overrides used
   * @param days the days, in order
   * @param durationMs wall time
   */
  public record Result(
      int appId,
      @Nullable Long serviceId,
      LocalDate from,
      LocalDate to,
      int appTMs,
      int overrides,
      List<DayResult> days,
      long durationMs) {

    public Result {
      days = List.copyOf(days);
    }

    /** Transactions re-stamped over the range. */
    public long rowsChanged() {
      return days.stream().mapToLong(DayResult::rowsChanged).sum();
    }
  }

  /**
   * Checks the range (no database access).
   *
   * @throws IllegalArgumentException for {@code from > to}, a day after today or more than {@link
   *     #MAX_DAYS} days
   * @throws RollupRebuildException when {@code from} is outside the raw retention
   */
  public static void checkRange(LocalDate from, LocalDate to, SchemaParameters params, Clock clock)
      throws RollupRebuildException {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("--from " + from + " is after --to " + to);
    }
    if (to.isAfter(today)) {
      throw new IllegalArgumentException("--to " + to + " is after today (" + today + ", UTC)");
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
      throw new IllegalArgumentException("The range may cover at most " + MAX_DAYS + " days");
    }
    int days = params.retention("xlog-days");
    if (!from.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(clock.instant())) {
      throw new RollupRebuildException(
          "Refused: "
              + from
              + " is outside the raw retention (argos.retention.xlog "
              + days
              + " days); only transactions still stored can be re-stamped");
    }
  }

  /**
   * The T of a transaction as a ClickHouse expression on {@code service_id}: a constant for one
   * service or an application without overrides, otherwise {@code transform} over the overrides.
   */
  static String tExpression(AppApdex apdex, @Nullable Long serviceId) {
    if (serviceId != null) {
      return "toUInt16(" + apdex.tFor(serviceId) + ")";
    }
    if (apdex.overrides().isEmpty()) {
      return "toUInt16(" + apdex.appTMs() + ")";
    }
    StringJoiner ids = new StringJoiner(", ", "[", "]");
    StringJoiner ts = new StringJoiner(", ", "[", "]");
    for (Map.Entry<Long, Integer> e : apdex.overrides().entrySet()) {
      ids.add(Long.toUnsignedString(e.getKey()));
      ts.add(Integer.toString(e.getValue()));
    }
    return "transform(service_id, CAST("
        + ids
        + " AS Array(UInt64)), CAST("
        + ts
        + " AS Array(UInt16)), toUInt16("
        + apdex.appTMs()
        + "))";
  }

  /** The predicate of the rows of one day ({@code toDate(ts)}, app, optional service). */
  static String rowPredicate(int appId, @Nullable Long serviceId, LocalDate day) {
    return "toDate(ts) = '"
        + day
        + "' AND app_id = "
        + Integer.toUnsignedString(appId)
        + (serviceId == null ? "" : " AND service_id = " + Long.toUnsignedString(serviceId));
  }

  /** The mutation of one day. */
  static String updateStatement(
      SchemaParameters params, int appId, @Nullable Long serviceId, LocalDate day, String t) {
    return "ALTER TABLE "
        + params.table("xlog")
        + " UPDATE apdex_t_ms = "
        + t
        + " IN PARTITION '"
        + day
        + "' WHERE "
        + rowPredicate(appId, serviceId, day)
        + " AND apdex_t_ms != "
        + t
        + " SETTINGS mutations_sync = 2";
  }

  /**
   * Re-stamps and rebuilds every day of the range (see the class comment). The caller holds the
   * rollup lock.
   *
   * @throws IllegalArgumentException for an invalid range
   * @throws RollupRebuildException when the range is outside the retention or a rebuild failed
   * @throws StatementException when a mutation failed
   */
  public Result reapply(
      int appId, @Nullable Long serviceId, LocalDate from, LocalDate to, AppApdex apdex)
      throws RollupRebuildException, StatementException {
    checkRange(from, to, params, clock);
    long start = System.nanoTime();
    String t = tExpression(apdex, serviceId);
    Set<String> tables = new LinkedHashSet<>(rollups);
    List<DayResult> days = new ArrayList<>();
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      List<String> count =
          sql.queryColumn(
              "SELECT toString(count()) FROM "
                  + params.table("xlog")
                  + " WHERE "
                  + rowPredicate(appId, serviceId, day)
                  + " AND apdex_t_ms != "
                  + t);
      long rows = count.isEmpty() ? 0 : Long.parseLong(count.get(0));
      if (rows > 0) {
        sql.execute(updateStatement(params, appId, serviceId, day, t));
      }
      RebuildResult rebuild = rebuilder.rebuildDay(day, tables);
      LOG.info(
          "Apdex reapply of app {}{} on {}: {} transactions re-stamped (T {} ms, {} service"
              + " overrides), rollups rebuilt, changed: {}",
          appId,
          serviceId == null ? "" : " service " + Long.toUnsignedString(serviceId),
          day,
          rows,
          serviceId == null ? apdex.appTMs() : apdex.tFor(serviceId),
          serviceId == null ? apdex.overrides().size() : 0,
          rebuild.changedTables().isEmpty() ? "none" : String.join(", ", rebuild.changedTables()));
      days.add(new DayResult(day, rows, rebuild));
    }
    return new Result(
        appId,
        serviceId,
        from,
        to,
        apdex.appTMs(),
        serviceId == null ? apdex.overrides().size() : 0,
        days,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  /**
   * Runs the command with its databases: PostgreSQL connection, rollup lock, schema check, the
   * current T, the migration account's ClickHouse session.
   */
  public static Outcome<Result> run(
      ArgosProperties properties,
      MigrationSource source,
      int appId,
      @Nullable Long serviceId,
      LocalDate from,
      LocalDate to,
      Clock clock) {
    SchemaParameters params;
    try {
      params = SchemaParameters.from(properties);
      checkRange(from, to, params, clock);
    } catch (IllegalArgumentException e) {
      return new Outcome.Refused<>(Reason.USAGE, e.getMessage());
    } catch (RollupRebuildException e) {
      return new Outcome.Refused<>(Reason.FAILURE, e.getMessage());
    }
    RollupSelects selects = RollupSelects.of(source);
    try (AdminDatabase db = AdminDatabase.open(properties.postgres())) {
      if (!db.tryRollupLock()) {
        return new Outcome.Refused<>(
            Reason.BUSY,
            "Another rollup rebuild, consistency check or Apdex reapply is running (PostgreSQL"
                + " advisory lock held); try again when it has finished");
      }
      List<String> pending = ReconcileService.pendingSchema(db, selects);
      if (!pending.isEmpty()) {
        return new Outcome.Refused<>(
            Reason.FAILURE,
            "The ClickHouse schema is behind this server version (not applied: "
                + String.join(", ", pending)
                + "); start the server of this version once to migrate, then reapply");
      }
      AppApdex apdex = db.apdex(appId).orElse(null);
      if (apdex == null) {
        return new Outcome.Refused<>(Reason.FAILURE, "There is no application with id " + appId);
      }
      Map<String, String> aliases = db.rollupAliases();
      try (RollupPartitions.Session session =
          RollupPartitions.open(properties.clickhouse(), RollupRebuilder.STATEMENT_TIMEOUT)) {
        RollupRebuilder rebuilder = new RollupRebuilder(selects, params, session, clock);
        Result result =
            new ApdexReapplier(session, params, rebuilder, aliases, clock)
                .reapply(appId, serviceId, from, to, apdex);
        return new Outcome.Done<>(result);
      }
    } catch (IllegalArgumentException e) {
      return new Outcome.Refused<>(Reason.USAGE, e.getMessage());
    } catch (AdminDatabaseException
        | RollupRebuildException
        | StatementException
        | IllegalStateException e) {
      return new Outcome.Refused<>(Reason.FAILURE, e.getMessage());
    }
  }
}
