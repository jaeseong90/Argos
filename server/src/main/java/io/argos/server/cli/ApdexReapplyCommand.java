/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.rollup.ApdexReapplier;
import io.argos.server.ops.rollup.ApdexReapplier.DayResult;
import io.argos.server.ops.rollup.ApdexReapplier.Result;
import io.argos.server.ops.rollup.Outcome;
import java.io.PrintStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin apdex reapply --app <id> [--service <id>] --from <yyyy-MM-dd> --to
 * <yyyy-MM-dd> [--json]} (D-17, WO-27 PM question 2): re-stamps the stored transactions of the days
 * with the current Apdex T and rebuilds their Apdex rollups ({@link ApdexReapplier}). Only inside
 * the raw retention; nothing else runs it.
 *
 * <p>Output on standard output: a table, or with {@code --json} one object ({@code command}, {@code
 * app_id}, {@code service_id} (unsigned decimal string or null), {@code from}, {@code to}, {@code
 * apdex_t_ms}, {@code overrides}, {@code rows_changed}, {@code duration_ms}, {@code days[]} with
 * {@code day}, {@code rows_changed}, {@code changed_tables[]}); a failure with {@code --json}
 * prints {@code {"command", "error"}}. Exit codes 0, 1 (refused or failed), 2 (invalid arguments).
 */
final class ApdexReapplyCommand {

  static final String USAGE =
      """
      Usage: argos-admin apdex reapply --app <id> [--service <id>] --from <yyyy-MM-dd> \
      --to <yyyy-MM-dd> [--json]
        Re-stamps the stored transactions of the application (or one service) on the UTC days
        with the current Apdex T and rebuilds their Apdex rollups. Raw retention only.
      """;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String COMMAND = "apdex reapply";

  /**
   * The parsed options.
   *
   * @param appId the application
   * @param serviceId the service (signed long of the unsigned id), or null for all
   * @param from first day
   * @param to last day
   * @param json JSON output
   */
  record Options(int appId, @Nullable Long serviceId, LocalDate from, LocalDate to, boolean json) {}

  private ApdexReapplyCommand() {}

  /**
   * Parses the arguments after {@code apdex reapply}.
   *
   * @throws CliUsageException for a missing, repeated, unknown or invalid option, or {@code --from}
   *     after {@code --to}
   */
  static Options parse(List<String> args) throws CliUsageException {
    Integer app = null;
    Long service = null;
    LocalDate from = null;
    LocalDate to = null;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      switch (a.name()) {
        case "--app" -> {
          if (app != null) {
            throw new CliUsageException("--app given twice");
          }
          app = a.appId();
        }
        case "--service" -> {
          if (service != null) {
            throw new CliUsageException("--service given twice");
          }
          service = a.unsignedId();
        }
        case "--from" -> {
          if (from != null) {
            throw new CliUsageException("--from given twice");
          }
          from = a.date();
        }
        case "--to" -> {
          if (to != null) {
            throw new CliUsageException("--to given twice");
          }
          to = a.date();
        }
        case "--json" -> {
          a.noValue();
          json = true;
        }
        default -> throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    if (app == null) {
      throw new CliUsageException("--app is required");
    }
    if (from == null || to == null) {
      throw new CliUsageException("--from and --to are required");
    }
    if (from.isAfter(to)) {
      throw new CliUsageException("--from " + from + " is after --to " + to);
    }
    return new Options(app, service, from, to, json);
  }

  /**
   * Runs the reapply and prints the result.
   *
   * @return the exit code
   */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    Outcome<Result> outcome =
        ApdexReapplier.run(
            properties,
            MigrationSource.fromClasspath(ApdexReapplyCommand.class.getClassLoader()),
            options.appId(),
            options.serviceId(),
            options.from(),
            options.to(),
            Clock.systemUTC());
    if (outcome instanceof Outcome.Refused<Result> refused) {
      return fail(
          options,
          refused.message(),
          out,
          err,
          refused.reason() == Outcome.Reason.USAGE ? AdminCli.EXIT_USAGE : AdminCli.EXIT_FAILURE);
    }
    Result result = ((Outcome.Done<Result>) outcome).value();
    out.print(options.json() ? json(result) + System.lineSeparator() : table(result));
    out.flush();
    return AdminCli.EXIT_OK;
  }

  private static int fail(
      Options options, String message, PrintStream out, PrintStream err, int code) {
    if (options.json()) {
      ObjectNode node = JSON.createObjectNode();
      node.put("command", COMMAND);
      node.put("error", message);
      out.println(JSON.writeValueAsString(node));
      out.flush();
    }
    err.println("argos-admin " + COMMAND + ": " + message);
    err.flush();
    return code;
  }

  /** The JSON document of a result (see the class comment). */
  static String json(Result result) {
    ObjectNode root = JSON.createObjectNode();
    root.put("command", COMMAND);
    root.put("app_id", result.appId());
    if (result.serviceId() == null) {
      root.putNull("service_id");
    } else {
      root.put("service_id", Long.toUnsignedString(result.serviceId()));
    }
    root.put("from", result.from().toString());
    root.put("to", result.to().toString());
    root.put("apdex_t_ms", result.appTMs());
    root.put("overrides", result.overrides());
    root.put("rows_changed", result.rowsChanged());
    root.put("duration_ms", result.durationMs());
    ArrayNode days = root.putArray("days");
    for (DayResult d : result.days()) {
      ObjectNode node = days.addObject();
      node.put("day", d.day().toString());
      node.put("rows_changed", d.rowsChanged());
      ArrayNode changed = node.putArray("changed_tables");
      d.rebuild().changedTables().forEach(changed::add);
    }
    return JSON.writeValueAsString(root);
  }

  /** The table output of a result. */
  static String table(Result result) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "Apdex reapply of app %d%s, %s to %s (UTC): T %d ms, %d service overrides,"
                + " %d transactions re-stamped, %d ms%n",
            result.appId(),
            result.serviceId() == null
                ? ""
                : " service " + Long.toUnsignedString(result.serviceId()),
            result.from(),
            result.to(),
            result.appTMs(),
            result.overrides(),
            result.rowsChanged(),
            result.durationMs()));
    String format = "%-10s %14s  %s%n";
    sb.append(String.format(Locale.ROOT, format, "DAY", "RE-STAMPED", "CHANGED ROLLUPS"));
    for (DayResult d : result.days()) {
      sb.append(
          String.format(
              Locale.ROOT,
              format,
              d.day(),
              d.rowsChanged(),
              d.rebuild().changedTables().isEmpty()
                  ? "none"
                  : String.join(", ", d.rebuild().changedTables())));
    }
    return sb.toString();
  }
}
