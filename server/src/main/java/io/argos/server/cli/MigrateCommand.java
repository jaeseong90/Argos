/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.AuditResult;
import io.argos.server.audit.StartupAudit;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.ClickHouseMigrationPhase;
import io.argos.server.ops.migration.ClickHouseMigrationRunner;
import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.PostgresMigrationPhase;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin migrate [--json]} (17 §4.4, §4.3 step 8; 13 §13): the two schema migration
 * steps of the server start, and nothing else: PostgreSQL with Flyway as the schema owner ({@link
 * PostgresMigrationPhase}, including the monthly partitions), then the ClickHouse runner as the
 * migration account ({@link ClickHouseMigrationPhase}). The same code, settings and locks as the
 * start, so a second run (or a start after it) applies nothing; running it next to a running server
 * of the same version is harmless.
 *
 * <p>Output: per store the schema version, the versions applied now and the time; JSON {@code
 * {"command", "postgres": {"schema_version", "applied": [{"version", "description",
 * "duration_ms"}]}, "clickhouse": {"applied": [...], "repaired": [...]}, "duration_ms"}}. Audit:
 * one {@code MIGRATION_APPLIED} per applied version (the start's path, {@link
 * StartupAudit#migrations}) and one {@code ADMIN_COMMAND_EXECUTED} for the run (also when nothing
 * was applied or a step failed). Exit codes 0 success, 1 failure (refused validation, database), 2
 * invalid arguments.
 */
final class MigrateCommand {

  static final String USAGE =
      """
      Usage: argos-admin migrate [--json]
        Applies the bundled PostgreSQL (Flyway, schema owner account) and ClickHouse (migration
        account) schema versions, as a server start does. Idempotent.
        Exit codes: 0 success, 1 failure, 2 invalid arguments.
      """;

  static final String COMMAND = "migrate";

  private MigrateCommand() {}

  /** Parses the options; only {@code --json}. */
  static boolean parse(List<String> args) throws CliUsageException {
    return VersionCommand.parse(args);
  }

  /** Runs both steps. */
  static int execute(boolean json, ArgosProperties properties, PrintStream out, PrintStream err) {
    long start = System.nanoTime();
    PostgresMigrationPhase postgres = new PostgresMigrationPhase(properties);
    ClickHouseMigrationPhase clickhouse =
        new ClickHouseMigrationPhase(
            properties, MigrationSource.fromClasspath(MigrateCommand.class.getClassLoader()));
    String failedStep = null;
    String failure = null;
    try {
      err.println("argos-admin migrate: PostgreSQL ...");
      postgres.run();
      err.println("argos-admin migrate: ClickHouse ...");
      clickhouse.run();
    } catch (StartupPhaseException | RuntimeException e) {
      failedStep = postgres.lastResult() == null ? "postgres" : "clickhouse";
      failure = oneLine(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    PostgresMigrationPhase.Result pg = postgres.lastResult();
    ClickHouseMigrationRunner.Result ch = clickhouse.lastResult();
    try (CliAudit audit = CliAudit.open(properties)) {
      StartupAudit.migrations(audit.service(), pg, ch, CliAudit.actor());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("postgres_applied", pg == null ? 0 : pg.applied().size());
      after.put("clickhouse_applied", ch == null ? 0 : ch.applied().size());
      after.put("duration_ms", ms);
      if (failedStep != null) {
        after.put("failed_step", failedStep);
      }
      audit.executed(
          COMMAND,
          failedStep == null ? AuditResult.success() : AuditResult.failure(failedStep + "_failed"),
          after);
    }
    if (failure != null) {
      return CliOutput.fail(
          json,
          COMMAND,
          failedStep + ": " + failure,
          failedStep + "_failed",
          out,
          err,
          AdminCli.EXIT_FAILURE);
    }
    if (json) {
      ObjectNode node = CliOutput.result(COMMAND);
      ObjectNode p = node.putObject("postgres");
      p.put("schema_version", pg.schemaVersion());
      ArrayNode pa = p.putArray("applied");
      for (PostgresMigrationPhase.Applied a : pg.applied()) {
        ObjectNode o = pa.addObject();
        o.put("version", a.version());
        o.put("description", a.description());
        o.put("duration_ms", a.executionTimeMs());
      }
      ObjectNode c = node.putObject("clickhouse");
      ArrayNode ca = c.putArray("applied");
      for (ClickHouseMigrationRunner.Applied a : ch.applied()) {
        ObjectNode o = ca.addObject();
        o.put("version", a.version());
        o.put("description", a.description());
        o.put("duration_ms", a.durationMs());
      }
      ArrayNode repaired = c.putArray("repaired");
      ch.repaired().forEach(repaired::add);
      node.put("duration_ms", ms);
      CliOutput.print(node, out);
    } else {
      out.printf(
          Locale.ROOT,
          "PostgreSQL: schema version %s, %d versions applied%n",
          pg.schemaVersion(),
          pg.applied().size());
      for (PostgresMigrationPhase.Applied a : pg.applied()) {
        out.printf(
            Locale.ROOT, "  V%s %s (%d ms)%n", a.version(), a.description(), a.executionTimeMs());
      }
      out.printf(Locale.ROOT, "ClickHouse: %d versions applied%n", ch.applied().size());
      for (ClickHouseMigrationRunner.Applied a : ch.applied()) {
        out.printf(Locale.ROOT, "  %s %s (%d ms)%n", a.version(), a.description(), a.durationMs());
      }
      out.printf(Locale.ROOT, "Done in %d ms%n", ms);
      out.flush();
    }
    return AdminCli.EXIT_OK;
  }

  private static String oneLine(String text) {
    String t = text.replaceAll("[\\r\\n\\t]+", " ").strip();
    return t.length() > 400 ? t.substring(0, 397) + "..." : t;
  }
}
