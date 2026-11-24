/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.DefaultAuditService;
import io.argos.server.audit.Target;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.retention.RetentionApplier;
import io.argos.server.ops.retention.RetentionApplier.Drop;
import io.argos.server.ops.retention.RetentionApplier.TableState;
import io.argos.server.ops.retention.RetentionPlanner.TtlChange;
import io.argos.server.ops.retention.RetentionSettings;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import io.argos.server.storage.clickhouse.TableCatalog;
import io.argos.server.storage.postgres.ops.OpsDatabase;
import io.argos.server.storage.postgres.ops.OpsDatabase.OpsDatabaseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin retention show [--json]} and {@code argos-admin retention apply [--json]} (17
 * §4.4, §9.1; WO-28): the effective ClickHouse retention ({@code argos.retention.*} with the values
 * stored by {@code PUT /api/v1/admin/settings/retention} in place, PM question 3 assumption).
 *
 * <ul>
 *   <li>{@code show}: per governed table the configured retention, the TTL of the table now, the
 *       oldest partition, the partition count, rows and bytes on disk (read only, no audit: it
 *       changes nothing).
 *   <li>{@code apply}: {@code MODIFY TTL} of every table whose TTL differs (no part rewritten),
 *       then drops at once the partitions of shortened tables that are outside the new retention
 *       (the running server spreads its drops over minutes; the operator asked for this one now).
 *       One {@code RETENTION_CHANGED} audit row (actor {@code argos-admin}) when anything changed.
 * </ul>
 *
 * Output on standard output: a table, or with {@code --json} one object ({@code command}, {@code
 * values} by API field, {@code sources}, and {@code tables[]} for show; {@code changes[]} with
 * {@code table}, {@code from_days}, {@code to_days} and {@code dropped[]} with {@code table},
 * {@code partition}, {@code rows}, {@code bytes} for apply); a failure with {@code --json} prints
 * {@code {"command", "error"}}. Exit codes 0, 1 (database or ClickHouse failure), 2 (invalid
 * arguments).
 */
final class RetentionCommand {

  static final String USAGE =
      """
      Usage: argos-admin retention show [--json]
             argos-admin retention apply [--json]
        show: the effective retention, each ClickHouse table's TTL, oldest partition and size.
        apply: gives every table the effective TTL (no part is rewritten) and drops the
        partitions outside a shortened retention at once.
      """;

  /** Audit actor of {@code apply}. */
  static final String ACTOR = "argos-admin";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The parsed options.
   *
   * @param apply {@code apply}, else {@code show}
   * @param json JSON output
   */
  record Options(boolean apply, boolean json) {

    String command() {
      return apply ? "retention apply" : "retention show";
    }
  }

  private RetentionCommand() {}

  /**
   * Parses the arguments after {@code retention show|apply}.
   *
   * @throws CliUsageException for an unknown or repeated option
   */
  static Options parse(boolean apply, List<String> args) throws CliUsageException {
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      if (a.name().equals("--json")) {
        if (json) {
          throw new CliUsageException("--json given twice");
        }
        a.noValue();
        json = true;
      } else {
        throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    return new Options(apply, json);
  }

  /**
   * Runs the command and prints the result.
   *
   * @return the exit code
   */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    SchemaParameters params;
    RetentionSettings config;
    try {
      params = SchemaParameters.from(properties);
      config = RetentionSettings.of(properties.retention());
    } catch (IllegalArgumentException e) {
      return fail(options, e.getMessage(), out, err);
    }
    try (OpsDatabase db = OpsDatabase.open(properties.postgres())) {
      Map<String, Integer> overrides = db.settings().retentionOverrides();
      RetentionSettings effective = config.with(overrides);
      Map<String, String> sources = new LinkedHashMap<>();
      for (String f : RetentionSettings.FIELDS) {
        sources.put(f, overrides.containsKey(f) ? "settings" : "config");
      }
      RetentionApplier applier = new RetentionApplier(params);
      try (RollupPartitions.Session sql =
              RollupPartitions.open(properties.clickhouse(), Duration.ofMinutes(30));
          TableCatalog.ReaderSession reader =
              TableCatalog.openReader(properties.clickhouse(), Duration.ofMinutes(1))) {
        if (!options.apply()) {
          List<TableState> tables = applier.show(sql, reader, effective);
          out.print(
              options.json()
                  ? showJson(effective, sources, tables) + System.lineSeparator()
                  : showTable(effective, sources, tables));
          out.flush();
          return AdminCli.EXIT_OK;
        }
        RetentionApplier.Result result =
            applier.apply(sql, reader, effective, LocalDate.now(ZoneOffset.UTC));
        List<Drop> dropped = new ArrayList<>();
        for (Drop d : result.drops()) {
          applier.drop(sql, d);
          dropped.add(d);
          err.println(
              "argos-admin retention apply: dropped partition "
                  + d.partitionId()
                  + " of "
                  + d.table());
        }
        if (!result.changes().isEmpty()) {
          Map<String, Object> before = new LinkedHashMap<>();
          Map<String, Object> after = new LinkedHashMap<>();
          for (TtlChange c : result.changes()) {
            before.put("ttl_" + c.table(), c.fromDays());
            after.put("ttl_" + c.table(), c.toDays());
          }
          after.put("dropped_partitions", dropped.size());
          new DefaultAuditService(List.of(db.auditSink()), new SimpleMeterRegistry())
              .record(
                  AuditEvent.RETENTION_CHANGED,
                  new Actor(Actor.Type.SYSTEM, ACTOR, ACTOR, null, null),
                  new Target("retention", null, "clickhouse"),
                  AuditResult.success(),
                  before,
                  after);
        }
        out.print(
            options.json()
                ? applyJson(effective, sources, result, dropped) + System.lineSeparator()
                : applyTable(result, dropped));
        out.flush();
        return AdminCli.EXIT_OK;
      }
    } catch (OpsDatabaseException e) {
      return fail(options, e.getMessage(), out, err);
    } catch (StatementException e) {
      return fail(options, e.getMessage(), out, err);
    } catch (IllegalStateException e) {
      return fail(options, e.getMessage(), out, err);
    } catch (DataAccessException e) {
      return fail(options, "PostgreSQL: " + e.getClass().getSimpleName(), out, err);
    }
  }

  private static int fail(Options options, String message, PrintStream out, PrintStream err) {
    String text = message == null ? "failed" : message.replaceAll("[\\r\\n\\t]+", " ");
    if (options.json()) {
      ObjectNode node = JSON.createObjectNode();
      node.put("command", options.command());
      node.put("error", text);
      out.println(JSON.writeValueAsString(node));
      out.flush();
    }
    err.println("argos-admin " + options.command() + ": " + text);
    err.flush();
    return AdminCli.EXIT_FAILURE;
  }

  private static ObjectNode values(ObjectNode root, RetentionSettings s, Map<String, String> src) {
    ObjectNode values = root.putObject("values");
    s.asMap().forEach(values::put);
    ObjectNode sources = root.putObject("sources");
    src.forEach(sources::put);
    return root;
  }

  /** The JSON document of {@code show}. */
  static String showJson(
      RetentionSettings effective, Map<String, String> sources, List<TableState> tables) {
    ObjectNode root = JSON.createObjectNode();
    root.put("command", "retention show");
    values(root, effective, sources);
    ArrayNode array = root.putArray("tables");
    for (TableState t : tables) {
      ObjectNode o = array.addObject();
      o.put("table", t.table());
      o.put("field", t.field());
      o.put("current_days", t.currentDays());
      o.put("desired_days", t.desiredDays());
      if (t.oldestPartition() == null) {
        o.putNull("oldest_partition");
      } else {
        o.put("oldest_partition", t.oldestPartition());
      }
      o.put("partitions", t.partitions());
      o.put("rows", t.rows());
      o.put("bytes", t.bytes());
    }
    return JSON.writeValueAsString(root);
  }

  /** The table output of {@code show}. */
  static String showTable(
      RetentionSettings effective, Map<String, String> sources, List<TableState> tables) {
    StringBuilder sb = new StringBuilder("Retention (days):");
    effective
        .asMap()
        .forEach(
            (k, v) ->
                sb.append(' ')
                    .append(k)
                    .append('=')
                    .append(v)
                    .append(" (")
                    .append(sources.get(k))
                    .append(')'));
    sb.append(System.lineSeparator());
    String format = "%-22s %8s %8s %-10s %6s %14s %16s%n";
    sb.append(
        String.format(
            Locale.ROOT, format, "TABLE", "TTL", "WANTED", "OLDEST", "PARTS", "ROWS", "BYTES"));
    for (TableState t : tables) {
      sb.append(
          String.format(
              Locale.ROOT,
              format,
              t.table(),
              t.currentDays() < 0 ? "none" : Integer.toString(t.currentDays()),
              t.desiredDays(),
              t.oldestPartition() == null ? "-" : t.oldestPartition(),
              t.partitions(),
              t.rows(),
              t.bytes()));
    }
    return sb.toString();
  }

  /** The JSON document of {@code apply}. */
  static String applyJson(
      RetentionSettings effective,
      Map<String, String> sources,
      RetentionApplier.Result result,
      List<Drop> dropped) {
    ObjectNode root = JSON.createObjectNode();
    root.put("command", "retention apply");
    values(root, effective, sources);
    ArrayNode changes = root.putArray("changes");
    for (TtlChange c : result.changes()) {
      ObjectNode o = changes.addObject();
      o.put("table", c.table());
      o.put("from_days", c.fromDays());
      o.put("to_days", c.toDays());
    }
    ArrayNode drops = root.putArray("dropped");
    for (Drop d : dropped) {
      ObjectNode o = drops.addObject();
      o.put("table", d.table());
      o.put("partition", d.partitionId());
      o.put("rows", d.rows());
      o.put("bytes", d.bytes());
    }
    ArrayNode unmanaged = root.putArray("unmanaged");
    result.unmanaged().forEach(unmanaged::add);
    return JSON.writeValueAsString(root);
  }

  /** The table output of {@code apply}. */
  static String applyTable(RetentionApplier.Result result, List<Drop> dropped) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "Retention applied: %d table TTL(s) changed, %d partition(s) dropped%n",
            result.changes().size(),
            dropped.size()));
    for (TtlChange c : result.changes()) {
      sb.append(
          String.format(
              Locale.ROOT, "  %-22s %d -> %d days%n", c.table(), c.fromDays(), c.toDays()));
    }
    for (Drop d : dropped) {
      sb.append(
          String.format(
              Locale.ROOT,
              "  dropped %-14s %-10s %d rows%n",
              d.table(),
              d.partitionId(),
              d.rows()));
    }
    return sb.toString();
  }
}
