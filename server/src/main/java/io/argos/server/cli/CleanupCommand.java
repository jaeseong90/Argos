/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.AuditResult;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase.AdminCommandDatabaseException;
import io.argos.server.storage.postgres.ops.DictionaryCleanupRepository;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin cleanup instances|dictionary|commands [--json]} (17 §4.4, §9.3): one run of a
 * PostgreSQL clean-up now, with the server's own statements and settings.
 *
 * <ul>
 *   <li>{@code instances}: the daily instance retention (12 §4.3, SEC-14): instances silent for
 *       {@code argos.instance.archive-after} (7 days) are archived, archived ones silent for {@code
 *       argos.instance.purge-after} (30 days) deleted. The command line has no session registry, so
 *       an instance the server shows connected (UP or STALE) is left alone.
 *   <li>{@code dictionary}: dictionary entries not seen for {@value #DICTIONARY_DAYS} days are
 *       deleted (SEC-13, 13 §8; an entry seen again is recreated by the next batch that names it).
 *   <li>{@code commands}: the command retention (16 §5.3, SEC-22): commands older than {@code
 *       argos.retention.command-results} (30 days) with their results, and those beyond the newest
 *       {@code argos.commands.keep-per-instance} of an instance; queued and sent ones stay.
 * </ul>
 *
 * Output: the number of rows per action; JSON {@code {"command", "target", ...counts,
 * "duration_ms"}}. Audit: {@code ADMIN_COMMAND_EXECUTED} with the counts. Exit codes 0 success, 1
 * failure (database), 2 invalid arguments.
 */
final class CleanupCommand {

  static final String USAGE =
      """
      Usage: argos-admin cleanup instances|dictionary|commands [--json]
        instances:  archive instances silent for argos.instance.archive-after (7d), delete
                    archived ones silent for argos.instance.purge-after (30d)
        dictionary: delete dictionary entries not seen for 90 days
        commands:   delete commands older than argos.retention.command-results (30d) and
                    beyond argos.commands.keep-per-instance per instance
        Exit codes: 0 success, 1 failure, 2 invalid arguments.
      """;

  /**
   * Days after which an unseen dictionary entry is deleted (SEC-13, 17 §9.3; the nightly job's
   * value).
   */
  static final int DICTIONARY_DAYS = DictionaryCleanupRepository.UNSEEN_DAYS;

  /** What is cleaned. */
  enum Target {
    INSTANCES,
    DICTIONARY,
    COMMANDS;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * The parsed options.
   *
   * @param target what is cleaned
   * @param json {@code --json}
   */
  record Options(Target target, boolean json) {
    String command() {
      return "cleanup " + target.label();
    }
  }

  private CleanupCommand() {}

  /** Parses the options after {@code cleanup <target>}. */
  static Options parse(String target, List<String> args) throws CliUsageException {
    Target t =
        switch (target) {
          case "instances" -> Target.INSTANCES;
          case "dictionary" -> Target.DICTIONARY;
          case "commands" -> Target.COMMANDS;
          default ->
              throw new CliUsageException("Unknown cleanup target: " + CliArgs.shorten(target));
        };
    return new Options(t, VersionCommand.parse(args));
  }

  /** Runs the clean-up. */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    Clock clock = Clock.systemUTC();
    long start = System.nanoTime();
    Map<String, Object> counts = new LinkedHashMap<>();
    try (AdminCommandDatabase db = AdminCommandDatabase.open(properties.postgres())) {
      Instant now = clock.instant();
      switch (options.target()) {
        case INSTANCES -> {
          Duration archiveAfter = properties.instance().archiveAfter();
          Duration purgeAfter = properties.instance().purgeAfter();
          Set<Integer> connected = db.connectedInstanceIds();
          int archived =
              db.instanceRetention().archiveSilentSince(now.minus(archiveAfter), connected, now);
          int purged = db.instanceRetention().purgeSilentSince(now.minus(purgeAfter), connected);
          counts.put("archived", archived);
          counts.put("deleted", purged);
          counts.put("archive_after_days", archiveAfter.toDays());
          counts.put("purge_after_days", purgeAfter.toDays());
          counts.put("connected_skipped", connected.size());
        }
        case DICTIONARY -> {
          long deleted = db.deleteDictionaryUnseenSince(DictionaryCleanupRepository.cutoff(now));
          counts.put("deleted", deleted);
          counts.put("unseen_days", DICTIONARY_DAYS);
        }
        case COMMANDS -> {
          Duration retention = properties.retention().commandResults();
          int keep = properties.commands().keepPerInstance();
          int old = db.commands().deleteOlderThan(now.minus(retention));
          int trimmed = db.commands().trimPerInstance(keep);
          counts.put("deleted_expired", old);
          counts.put("deleted_surplus", trimmed);
          counts.put("retention_days", retention.toDays());
          counts.put("keep_per_instance", keep);
        }
      }
    } catch (AdminCommandDatabaseException e) {
      return failed(options, properties, e.getMessage(), out, err);
    } catch (DataAccessException e) {
      return failed(options, properties, "PostgreSQL: " + e.getClass().getSimpleName(), out, err);
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    counts.put("duration_ms", ms);
    CliAudit.executed(properties, options.command(), AuditResult.success(), counts);
    if (options.json()) {
      ObjectNode node = CliOutput.result(options.command());
      node.put("target", options.target().label());
      counts.forEach((k, v) -> node.putPOJO(k, v));
      CliOutput.print(node, out);
    } else {
      StringBuilder sb =
          new StringBuilder("Cleanup of ").append(options.target().label()).append(':');
      counts.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
      out.println(sb);
      out.flush();
    }
    return AdminCli.EXIT_OK;
  }

  private static int failed(
      Options options,
      ArgosProperties properties,
      String message,
      PrintStream out,
      PrintStream err) {
    CliAudit.executed(properties, options.command(), AuditResult.failure("database"), null);
    return CliOutput.fail(
        options.json(), options.command(), message, "database", out, err, AdminCli.EXIT_FAILURE);
  }
}
