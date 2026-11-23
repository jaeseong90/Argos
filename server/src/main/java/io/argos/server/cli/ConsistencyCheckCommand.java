/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.rollup.ConsistencyChecks;
import io.argos.server.ops.rollup.Outcome;
import io.argos.server.ops.rollup.ReconcileReport;
import io.argos.server.ops.rollup.ReconcileService;
import io.argos.server.storage.postgres.ops.ReconcileResultRepository.Row;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin consistency check --date <yyyy-MM-dd> [--rebuild] [--json]} (17 §4.4, 12 §9.1,
 * F-S22): the nightly consistency check of one UTC day on demand ({@link ReconcileService}, trigger
 * {@code command}), for example after a WAL replay or a restore. Without {@code --rebuild} it only
 * reports; with it the mismatching rollups of the day are rebuilt and checked again (at most two
 * rebuilds), as the nightly job does. The rows go to {@code reconcile_results} either way.
 *
 * <p>Output on standard output: a table, or with {@code --json} the document of {@link
 * ReconcileReport#json()} ({@code command}, {@code day}, {@code result}, {@code rebuild}, {@code
 * attempts}, {@code duration_ms}, {@code checks[]} with {@code app_id}, {@code check}, {@code
 * table}, {@code mismatch_minutes}, {@code rebuilt}, {@code result}, {@code detail}, {@code
 * tables_checked[]}, {@code skipped[]}); a failure with {@code --json} prints {@code {"command",
 * "day", "error"}}. Exit codes: 0 when no rollup mismatch remains (none found, or all repaired;
 * D-12 differences are reported but never repaired), 1 when one remains or the check failed, 2 for
 * invalid arguments.
 */
final class ConsistencyCheckCommand {

  static final String USAGE =
      """
      Usage: argos-admin consistency check --date <yyyy-MM-dd> [--rebuild] [--json]
        Compares the rollups of one UTC day with their sources (D-03, D-12) and records the result.
        --rebuild rebuilds the mismatching rollups and checks them again.
        Exit codes: 0 no rollup mismatch left, 1 a mismatch left or failure, 2 invalid arguments.
      """;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String COMMAND = "consistency check";

  /**
   * The parsed options.
   *
   * @param day the day to check
   * @param rebuild rebuild mismatching rollups
   * @param json JSON output
   */
  record Options(LocalDate day, boolean rebuild, boolean json) {}

  private ConsistencyCheckCommand() {}

  /**
   * Parses the arguments after {@code consistency check}.
   *
   * @throws CliUsageException for a missing, repeated, unknown or invalid option
   */
  static Options parse(List<String> args) throws CliUsageException {
    LocalDate day = null;
    boolean rebuild = false;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      switch (a.name()) {
        case "--date" -> {
          if (day != null) {
            throw new CliUsageException("--date given twice");
          }
          day = a.date();
        }
        case "--rebuild" -> {
          a.noValue();
          rebuild = true;
        }
        case "--json" -> {
          a.noValue();
          json = true;
        }
        default -> throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    if (day == null) {
      throw new CliUsageException("--date is required");
    }
    return new Options(day, rebuild, json);
  }

  /**
   * Runs the check and prints the result.
   *
   * @return the exit code
   */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    Outcome<ReconcileReport> outcome =
        ReconcileService.run(
            properties,
            MigrationSource.fromClasspath(ConsistencyCheckCommand.class.getClassLoader()),
            options.day(),
            ReconcileReport.COMMAND,
            options.rebuild(),
            false,
            Clock.systemUTC());
    if (outcome instanceof Outcome.Refused<ReconcileReport> refused) {
      return fail(
          options,
          refused.message(),
          out,
          err,
          refused.reason() == Outcome.Reason.USAGE ? AdminCli.EXIT_USAGE : AdminCli.EXIT_FAILURE);
    }
    ReconcileReport report = ((Outcome.Done<ReconcileReport>) outcome).value();
    if (options.json()) {
      out.println(report.json());
    } else {
      out.print(table(report));
    }
    out.flush();
    return report.remainingTables().isEmpty() ? AdminCli.EXIT_OK : AdminCli.EXIT_FAILURE;
  }

  private static int fail(
      Options options, String message, PrintStream out, PrintStream err, int code) {
    if (options.json()) {
      ObjectNode node = JSON.createObjectNode();
      node.put("command", COMMAND);
      node.put("day", options.day().toString());
      node.put("error", message);
      out.println(JSON.writeValueAsString(node));
      out.flush();
    }
    err.println("argos-admin " + COMMAND + ": " + message);
    err.flush();
    return code;
  }

  /** The table output of a report. */
  static String table(ReconcileReport report) {
    StringBuilder sb = new StringBuilder();
    List<Row> rows = report.rows(Instant.EPOCH);
    sb.append(
        String.format(
            Locale.ROOT,
            "Consistency check of %s (UTC): %d comparisons, result %s, %d rebuilds, %d ms%n",
            report.day(),
            report.tables().size() + report.d12().size(),
            report.result(),
            report.rebuilds().size(),
            report.durationMs()));
    String format = "%-10s %-10s %-20s %10s %-12s%n";
    sb.append(String.format(Locale.ROOT, format, "APP", "CHECK", "TABLE", "MINUTES", "RESULT"));
    for (Row row : rows) {
      if (row.appId() == null) {
        continue;
      }
      sb.append(
          String.format(
              Locale.ROOT,
              format,
              row.appId(),
              row.checkName(),
              row.tableName(),
              row.mismatchMinutes(),
              row.rebuildResult()));
    }
    if (rows.size() == 1) {
      sb.append("No mismatch.").append(System.lineSeparator());
    }
    for (String skipped : report.skipped()) {
      sb.append("Skipped: ").append(skipped).append(System.lineSeparator());
    }
    if (report.rebuildFailure() != null) {
      sb.append("Rebuild failed: ").append(report.rebuildFailure()).append(System.lineSeparator());
    }
    if (!report.rebuildEnabled() && !report.mismatchingTables().isEmpty()) {
      sb.append("Run again with --rebuild to repair: ")
          .append(String.join(", ", report.mismatchingTables()))
          .append(System.lineSeparator());
    }
    if (report.d12().stream().anyMatch(c -> !c.mismatchingApps().isEmpty())) {
      sb.append("D-12 differences (")
          .append(ConsistencyChecks.D12)
          .append(") are recorded only: suspected collection loss, a rebuild cannot repair them.")
          .append(System.lineSeparator());
    }
    return sb.toString();
  }
}
