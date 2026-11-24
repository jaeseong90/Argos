/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.AuditEvent;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.RollupSelects;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.rollup.RebuildResult;
import io.argos.server.ops.rollup.RebuildResult.TableResult;
import io.argos.server.ops.rollup.RollupAudit;
import io.argos.server.ops.rollup.RollupRebuildException;
import io.argos.server.ops.rollup.RollupRebuilder;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.Digest;
import io.argos.server.storage.postgres.ops.AdminDatabase;
import java.io.PrintStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin rollup rebuild --date <yyyy-MM-dd> [--table <t>]... [--json]} (12 §9.1, 17
 * §4.4, ADR-0007 C-59): rebuilds the rollup partitions of one UTC day with {@link RollupRebuilder}
 * under the rollup lock of {@link AdminDatabase}, after checking that every bundled ClickHouse
 * schema script is applied. {@code --table} takes logical rollup names ({@code
 * settings.rollup_alias} resolves them) and may repeat; without it every rollup is rebuilt. There
 * is no {@code --app} (C-59 (c)).
 *
 * <p>Output on standard output: a table, or with {@code --json} one JSON object ({@code command},
 * {@code day}, {@code identical}, {@code duration_ms}, {@code tables[]} with {@code table}, {@code
 * level}, {@code partition}, {@code staging_rows}, {@code dropped}, {@code identical}, {@code
 * before} and {@code after} ({@code keys}, {@code hash}), {@code excluded_columns}, {@code
 * duration_ms}); a failure with {@code --json} prints {@code {"command", "error"}}. No secret value
 * is ever printed.
 *
 * <p>Audit (ADR-0010 C-220 (3)): one {@code ROLLUP_REBUILT} row per run that got past the argument
 * check, with the tables and the changed ones, or with result {@code failure} and the reason
 * ({@code busy}, {@code usage}, {@code failure}) when it did not rebuild ({@link RollupAudit}).
 */
final class RollupRebuildCommand {

  static final String USAGE =
      """
      Usage: argos-admin rollup rebuild --date <yyyy-MM-dd> [--table <rollup>]... [--json]
        Rebuilds the rollup partitions of one UTC day: every 1m rollup for the day and every 1h
        rollup for its month. --table limits it; a 1m table brings its 1h cascade along.
      """;

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern TABLE = Pattern.compile("[a-z][a-z0-9_]{0,127}");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String COMMAND = "rollup rebuild";

  /**
   * The parsed options.
   *
   * @param day the day to rebuild
   * @param tables logical rollup names, empty for all
   * @param json JSON output
   */
  record Options(LocalDate day, Set<String> tables, boolean json) {

    Options {
      tables = Set.copyOf(tables);
    }
  }

  private RollupRebuildCommand() {}

  /**
   * Parses the arguments after {@code rollup rebuild}.
   *
   * @throws CliUsageException for a missing, repeated, unknown or invalid option
   */
  static Options parse(List<String> args) throws CliUsageException {
    LocalDate day = null;
    Set<String> tables = new LinkedHashSet<>();
    boolean json = false;
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      String value = null;
      String name = arg;
      int eq = arg.indexOf('=');
      if (arg.startsWith("--") && eq > 0) {
        name = arg.substring(0, eq);
        value = arg.substring(eq + 1);
      }
      switch (name) {
        case "--date" -> {
          if (day != null) {
            throw new CliUsageException("--date given twice");
          }
          if (value == null) {
            value = next(args, ++i, "--date");
          }
          try {
            day = LocalDate.parse(value, DATE);
          } catch (DateTimeParseException e) {
            throw new CliUsageException("--date must be a date yyyy-MM-dd, got: " + shorten(value));
          }
        }
        case "--table" -> {
          if (value == null) {
            value = next(args, ++i, "--table");
          }
          if (!TABLE.matcher(value).matches()) {
            throw new CliUsageException(
                "--table must be a rollup table name, got: " + shorten(value));
          }
          tables.add(value);
        }
        case "--json" -> {
          if (value != null) {
            throw new CliUsageException("--json takes no value");
          }
          json = true;
        }
        default -> throw new CliUsageException("Unknown option: " + shorten(arg));
      }
    }
    if (day == null) {
      throw new CliUsageException("--date is required");
    }
    return new Options(day, tables, json);
  }

  private static String next(List<String> args, int index, String option) throws CliUsageException {
    if (index >= args.size() || args.get(index).startsWith("--")) {
      throw new CliUsageException(option + " needs a value");
    }
    return args.get(index);
  }

  private static String shorten(String text) {
    String oneLine = text.replaceAll("\\p{Cntrl}", "?");
    return oneLine.length() > 80 ? oneLine.substring(0, 77) + "..." : oneLine;
  }

  /**
   * Runs the rebuild and prints the result.
   *
   * @return the exit code
   */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    SchemaParameters params;
    try {
      params = SchemaParameters.from(properties);
    } catch (IllegalArgumentException e) {
      return fail(
          options,
          properties,
          "failure",
          "Configuration: " + e.getMessage(),
          out,
          err,
          AdminCli.EXIT_FAILURE);
    }
    RollupSelects selects =
        RollupSelects.of(
            MigrationSource.fromClasspath(RollupRebuildCommand.class.getClassLoader()));
    try (AdminDatabase db = AdminDatabase.open(properties.postgres())) {
      if (!db.tryRollupLock()) {
        return fail(
            options,
            properties,
            "busy",
            "Another rollup rebuild or consistency check is running (PostgreSQL advisory lock "
                + "held); try again when it has finished",
            out,
            err,
            AdminCli.EXIT_FAILURE);
      }
      Set<String> applied = db.appliedClickHouseVersions();
      List<String> pending = new ArrayList<>();
      for (String version : selects.schemaVersions()) {
        if (!applied.contains(version)) {
          pending.add(version);
        }
      }
      if (!pending.isEmpty()) {
        return fail(
            options,
            properties,
            "failure",
            "The ClickHouse schema is behind this server version (not applied: "
                + String.join(", ", pending)
                + "); start the server of this version once to migrate, then rebuild",
            out,
            err,
            AdminCli.EXIT_FAILURE);
      }
      Map<String, String> aliases = db.rollupAliases();
      try (RollupPartitions.Session session =
          RollupPartitions.open(properties.clickhouse(), RollupRebuilder.STATEMENT_TIMEOUT)) {
        RollupRebuilder rebuilder =
            new RollupRebuilder(selects, params, session, Clock.systemUTC());
        RebuildResult result =
            rebuilder.rebuildDay(options.day(), RollupRebuilder.resolve(options.tables(), aliases));
        if (options.json()) {
          out.println(json(result));
        } else {
          out.print(table(result));
        }
        out.flush();
        try (CliAudit audit = CliAudit.open(properties)) {
          RollupAudit.rebuilt(audit.service(), CliAudit.actor(), result);
        }
        return AdminCli.EXIT_OK;
      }
    } catch (IllegalArgumentException e) {
      return fail(options, properties, "usage", e.getMessage(), out, err, AdminCli.EXIT_USAGE);
    } catch (AdminDatabase.AdminDatabaseException
        | RollupRebuildException
        | IllegalStateException e) {
      return fail(options, properties, "failure", e.getMessage(), out, err, AdminCli.EXIT_FAILURE);
    }
  }

  private static int fail(
      Options options,
      ArgosProperties properties,
      String reason,
      String message,
      PrintStream out,
      PrintStream err,
      int code) {
    try (CliAudit audit = CliAudit.open(properties)) {
      RollupAudit.refused(
          audit.service(),
          CliAudit.actor(),
          AuditEvent.ROLLUP_REBUILT,
          RollupAudit.day(options.day()),
          reason,
          RollupAudit.dayDetail(options.day(), options.tables().stream().sorted().toList()));
    }
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

  /** The JSON document of a result (see the class comment). */
  static String json(RebuildResult result) {
    ObjectNode root = JSON.createObjectNode();
    root.put("command", COMMAND);
    root.put("day", result.day().toString());
    root.put("identical", result.identical());
    root.put("duration_ms", result.durationMs());
    ArrayNode tables = root.putArray("tables");
    for (TableResult t : result.tables()) {
      ObjectNode node = tables.addObject();
      node.put("table", t.table());
      node.put("level", t.level().label());
      node.put("partition", t.partition());
      node.put("staging_rows", t.stagingRows());
      node.put("dropped", t.dropped());
      node.put("identical", t.identical());
      digest(node.putObject("before"), t.before());
      digest(node.putObject("after"), t.after());
      ArrayNode excluded = node.putArray("excluded_columns");
      t.after().excludedColumns().forEach(excluded::add);
      node.put("duration_ms", t.durationMs());
    }
    return JSON.writeValueAsString(root);
  }

  private static void digest(ObjectNode node, Digest digest) {
    node.put("keys", digest.keys());
    node.put("hash", digest.hash());
  }

  /** The table output of a result. */
  static String table(RebuildResult result) {
    StringBuilder sb = new StringBuilder();
    int changed = result.changedTables().size();
    sb.append(
        String.format(
            Locale.ROOT,
            "Rollup rebuild of %s (UTC): %d tables, %d identical, %d changed, %d ms%n",
            result.day(),
            result.tables().size(),
            result.tables().size() - changed,
            changed,
            result.durationMs()));
    String format = "%-24s %-5s %-9s %12s %12s %12s  %-9s %8s%n";
    sb.append(
        String.format(
            Locale.ROOT,
            format,
            "TABLE",
            "LEVEL",
            "PARTITION",
            "STAGING",
            "KEYS BEFORE",
            "KEYS AFTER",
            "RESULT",
            "MS"));
    for (TableResult t : result.tables()) {
      sb.append(
          String.format(
              Locale.ROOT,
              format,
              t.table(),
              t.level().label(),
              t.partition(),
              t.stagingRows(),
              t.before().keys(),
              t.after().keys(),
              t.identical() ? "identical" : (t.dropped() ? "dropped" : "changed"),
              t.durationMs()));
    }
    return sb.toString();
  }
}
