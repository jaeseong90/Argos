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
import io.argos.server.storage.postgres.ops.ReconcileResultRepository.Row;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The consistency check of a day with its databases (12 §9.1): opens the PostgreSQL connection of
 * {@code argos_app} ({@link AdminDatabase}), takes the rollup lock without waiting, checks that the
 * bundled ClickHouse schema is applied, opens the migration account's ClickHouse session, runs
 * {@link Reconciler} and writes the {@code reconcile_results} rows. Shared by the nightly {@link
 * ReconcileJob} and {@code argos-admin consistency check}; the cron run of a day that another node
 * or an earlier start already checked does nothing.
 */
public final class ReconcileService {

  private static final Logger LOG = LoggerFactory.getLogger(ReconcileService.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ReconcileService() {}

  /**
   * Checks {@code day}.
   *
   * @param properties the server configuration
   * @param source the bundled migration files (rollup SELECTs, schema versions)
   * @param day the UTC day
   * @param trigger {@link ReconcileReport#SCHEDULED} or {@link ReconcileReport#COMMAND}
   * @param rebuild rebuild the mismatching tables
   * @param oncePerDay do nothing when a scheduled run already recorded the day (the cron of every
   *     node; an explicit run checks again)
   * @param clock UTC clock
   */
  public static Outcome<ReconcileReport> run(
      ArgosProperties properties,
      MigrationSource source,
      LocalDate day,
      String trigger,
      boolean rebuild,
      boolean oncePerDay,
      Clock clock) {
    SchemaParameters params;
    try {
      params = SchemaParameters.from(properties);
    } catch (IllegalArgumentException e) {
      return new Outcome.Refused<>(Reason.FAILURE, "Configuration: " + e.getMessage());
    }
    RollupSelects selects = RollupSelects.of(source);
    try (AdminDatabase db = AdminDatabase.open(properties.postgres())) {
      if (!db.tryRollupLock()) {
        return new Outcome.Refused<>(
            Reason.BUSY,
            "Another rollup rebuild, consistency check or Apdex reapply is running (PostgreSQL"
                + " advisory lock held); try again when it has finished");
      }
      if (oncePerDay && db.reconcileResults().scheduledRunRecorded(day)) {
        return new Outcome.Refused<>(
            Reason.ALREADY_DONE, "The scheduled consistency check of " + day + " already ran");
      }
      List<String> pending = pendingSchema(db, selects);
      if (!pending.isEmpty()) {
        return new Outcome.Refused<>(
            Reason.FAILURE,
            "The ClickHouse schema is behind this server version (not applied: "
                + String.join(", ", pending)
                + "); start the server of this version once to migrate, then check");
      }
      Map<String, String> aliases = db.rollupAliases();
      ReconcileReport report;
      try (RollupPartitions.Session session =
          RollupPartitions.open(properties.clickhouse(), RollupRebuilder.STATEMENT_TIMEOUT)) {
        RollupRebuilder rebuilder = new RollupRebuilder(selects, params, session, clock);
        report =
            new Reconciler(session, params, aliases, rebuilder, clock).run(day, trigger, rebuild);
      } catch (StatementException | IllegalStateException e) {
        recordFailure(db, day, trigger, e.getMessage(), clock);
        return new Outcome.Refused<>(Reason.FAILURE, e.getMessage());
      }
      db.reconcileResults().insert(report.rows(clock.instant()));
      return new Outcome.Done<>(report);
    } catch (IllegalArgumentException e) {
      return new Outcome.Refused<>(Reason.USAGE, e.getMessage());
    } catch (AdminDatabaseException | IllegalStateException e) {
      return new Outcome.Refused<>(Reason.FAILURE, e.getMessage());
    }
  }

  /** The bundled schema versions that {@code ch_schema_history} does not list as applied. */
  static List<String> pendingSchema(AdminDatabase db, RollupSelects selects)
      throws AdminDatabaseException {
    var applied = db.appliedClickHouseVersions();
    List<String> pending = new ArrayList<>();
    for (String version : selects.schemaVersions()) {
      if (!applied.contains(version)) {
        pending.add(version);
      }
    }
    return pending;
  }

  /** A summary row {@code failed} for a run that could not check (best effort). */
  private static void recordFailure(
      AdminDatabase db, LocalDate day, String trigger, String message, Clock clock) {
    ObjectNode detail = JSON.createObjectNode();
    detail.put("trigger", trigger);
    detail.put("error", message == null ? "" : message);
    try {
      db.reconcileResults()
          .insert(
              List.of(
                  new Row(
                      day,
                      null,
                      "summary",
                      "*",
                      0,
                      false,
                      "failed",
                      clock.instant(),
                      JSON.writeValueAsString(detail))));
    } catch (AdminDatabaseException | RuntimeException e) {
      LOG.warn("Could not record the failed consistency check of {}: {}", day, e.getMessage());
    }
  }
}
