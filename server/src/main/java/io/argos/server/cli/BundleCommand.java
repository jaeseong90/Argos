/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.AuditResult;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.diagnostics.DiagnosticBundle;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.ConfigurableEnvironment;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin bundle [--since <n>h|<n>d] [--out <dir>] [--log-dir <dir>] [--json]} (12 §9.5,
 * 17 §4.4, SEC-06): writes the diagnostic bundle of {@link DiagnosticBundle} ({@code
 * argos-diag-<host>-<ts>.tar.gz}, owner-only) to {@code --out} (default the working directory).
 * {@code --since} is the log period, 1 hour to 7 days (default 24h); {@code --log-dir} the server
 * log directory when it is not {@code logging.file.path} or {@code logs/server}.
 *
 * <p>Output: the archive path, size and sections; JSON {@code {"command", "file", "bytes",
 * "sections": [{"name", "ok", "detail"}], "entries": [...]}}. Audit: {@code ADMIN_COMMAND_EXECUTED}
 * (file name, size, failed sections). Exit codes 0 written (also when a section could not be
 * collected; the manifest says which), 1 the archive could not be written, 2 invalid arguments.
 */
final class BundleCommand {

  static final String USAGE =
      """
      Usage: argos-admin bundle [--since <n>h|<n>d] [--out <dir>] [--log-dir <dir>] [--json]
        Writes argos-diag-<host>-<timestamp>.tar.gz (owner-only) with the masked server log of
        the period (default 24h, at most 7d), the effective configuration (secrets masked),
        versions, migration history, storage state, instances and token prefixes, system
        information. No secret, token or collected data is included.
        Exit codes: 0 written, 1 failure, 2 invalid arguments.
      """;

  static final String COMMAND = "bundle";

  private static final Pattern SINCE = Pattern.compile("([1-9][0-9]{0,3})([hd])");

  /**
   * The parsed options.
   *
   * @param since the log period
   * @param out the output directory
   * @param logDir the log directory, or null
   * @param json {@code --json}
   */
  record Options(Duration since, Path out, @Nullable Path logDir, boolean json) {}

  private BundleCommand() {}

  /** Parses the options after {@code bundle}. */
  static Options parse(List<String> args) throws CliUsageException {
    Duration since = null;
    Path out = null;
    Path logDir = null;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      switch (a.name()) {
        case "--since" -> {
          if (since != null) {
            throw new CliUsageException("--since given twice");
          }
          since = since(a.value());
        }
        case "--out" -> {
          if (out != null) {
            throw new CliUsageException("--out given twice");
          }
          out = path("--out", a.value());
        }
        case "--log-dir" -> {
          if (logDir != null) {
            throw new CliUsageException("--log-dir given twice");
          }
          logDir = path("--log-dir", a.value());
        }
        case "--json" -> {
          a.noValue();
          json = true;
        }
        default -> throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    return new Options(
        since == null ? DiagnosticBundle.DEFAULT_SINCE : since,
        out == null ? Path.of(".") : out,
        logDir,
        json);
  }

  static Duration since(String value) throws CliUsageException {
    Matcher m = SINCE.matcher(value);
    if (m.matches()) {
      long n = Long.parseLong(m.group(1));
      Duration d = m.group(2).equals("h") ? Duration.ofHours(n) : Duration.ofDays(n);
      if (d.compareTo(DiagnosticBundle.MAX_SINCE) <= 0) {
        return d;
      }
    }
    throw new CliUsageException(
        "--since must be hours or days up to 7 days (24h, 7d), got: " + CliArgs.shorten(value));
  }

  private static Path path(String option, String value) throws CliUsageException {
    try {
      return Path.of(value);
    } catch (InvalidPathException e) {
      throw new CliUsageException(option + " is not a valid path: " + CliArgs.shorten(value));
    }
  }

  /** Writes the bundle inside the configuration context. */
  static int execute(
      Options options,
      ArgosProperties properties,
      ConfigurableEnvironment environment,
      PrintStream out,
      PrintStream err) {
    DiagnosticBundle.Result result;
    try {
      result =
          new DiagnosticBundle(properties, environment, VersionCommand.build(), Clock.systemUTC())
              .write(
                  new DiagnosticBundle.Options(options.since(), options.out(), options.logDir()));
    } catch (IOException | RuntimeException e) {
      CliAudit.executed(properties, COMMAND, AuditResult.failure("write_failed"), null);
      return CliOutput.fail(
          options.json(),
          COMMAND,
          "The bundle cannot be written: " + e.getClass().getSimpleName(),
          "write_failed",
          out,
          err,
          AdminCli.EXIT_FAILURE);
    }
    List<String> failed =
        result.sections().stream()
            .filter(s -> !s.ok())
            .map(DiagnosticBundle.Section::name)
            .toList();
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("file", result.file().getFileName().toString());
    after.put("bytes", result.bytes());
    after.put("since", options.since().toString());
    after.put("failed_sections", failed);
    CliAudit.executed(properties, COMMAND, AuditResult.success(), after);
    if (options.json()) {
      ObjectNode node = CliOutput.result(COMMAND);
      node.put("file", result.file().toString());
      node.put("bytes", result.bytes());
      ArrayNode sections = node.putArray("sections");
      for (DiagnosticBundle.Section s : result.sections()) {
        ObjectNode o = sections.addObject();
        o.put("name", s.name());
        o.put("ok", s.ok());
        o.put("detail", s.detail());
      }
      ArrayNode entries = node.putArray("entries");
      result.entries().forEach(entries::add);
      CliOutput.print(node, out);
    } else {
      out.printf(
          Locale.ROOT,
          "Diagnostic bundle %s (%d bytes, owner-only)%n",
          result.file(),
          result.bytes());
      for (DiagnosticBundle.Section s : result.sections()) {
        out.printf(
            Locale.ROOT, "  %-10s %-6s %s%n", s.name(), s.ok() ? "ok" : "FAILED", s.detail());
      }
      out.flush();
    }
    return AdminCli.EXIT_OK;
  }
}
