/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditSinks;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ConfigKeyWarnings;
import io.argos.server.config.ConfigValidation;
import io.argos.server.config.CredentialPolicy;
import io.argos.server.config.EffectiveServerConfig;
import io.argos.server.config.TestingHooks;
import io.argos.server.secrets.MasterKey;
import io.argos.server.secrets.MasterKeyLoader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin config effective [--changed] [--json]} and {@code config check [--json]} (17
 * §4.4, §6.1): the configuration the server would start with, read from the same sources (packaged
 * {@code application.yml}, operator files, {@code ARGOS_*} environment, system properties, command
 * line).
 *
 * <ul>
 *   <li>{@code effective}: every {@code argos.*} key with its value and source ({@link
 *       EffectiveServerConfig}: secrets {@code ****}, every value through {@code LogMasking});
 *       {@code --changed} lists only the keys that differ from the defaults.
 *   <li>{@code check}: what the start would refuse, without starting: the constraint and cross-key
 *       rules ({@link ConfigValidation}), the database credential policy (SEC-74), the master key
 *       (missing, malformed, unreadable, too open; ADR-0010 C-226 (2)) and {@code
 *       argos.audit.sinks}; and what it would only warn about (unknown keys, a permissive master
 *       key file). Exit code 0 when nothing would refuse the start, 1 otherwise.
 * </ul>
 *
 * Neither prints a secret value. Both record {@code ADMIN_COMMAND_EXECUTED} (counts only).
 */
final class ConfigCommand {

  static final String USAGE =
      """
      Usage: argos-admin config effective [--changed] [--json]
             argos-admin config check [--json]
        effective: every argos.* setting with its value and source (secrets as ****).
        check: validates the configuration as a start would, without starting.
        Exit codes: 0 success (check: valid), 1 failure (check: the start would be refused),
        2 invalid arguments.
      """;

  /**
   * The parsed options.
   *
   * @param check {@code check} (else {@code effective})
   * @param changedOnly {@code --changed}
   * @param json {@code --json}
   */
  record Options(boolean check, boolean changedOnly, boolean json) {
    String command() {
      return check ? "config check" : "config effective";
    }
  }

  private ConfigCommand() {}

  /** Parses the options after {@code config effective} or {@code config check}. */
  static Options parse(boolean check, List<String> args) throws CliUsageException {
    boolean changed = false;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      switch (a.name()) {
        case "--json" -> {
          a.noValue();
          json = true;
        }
        case "--changed" -> {
          if (check) {
            throw new CliUsageException("--changed is an option of config effective");
          }
          a.noValue();
          changed = true;
        }
        default -> throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    return new Options(check, changed, json);
  }

  /** Runs the command inside the configuration context. */
  static int execute(
      Options options,
      ArgosProperties properties,
      ConfigurableEnvironment environment,
      PrintStream out,
      PrintStream err) {
    EffectiveServerConfig effective = new EffectiveServerConfig(environment, properties);
    return options.check()
        ? check(options, properties, environment, effective, out, err)
        : effective(options, properties, effective, out);
  }

  private static int effective(
      Options options,
      ArgosProperties properties,
      EffectiveServerConfig effective,
      PrintStream out) {
    List<EffectiveServerConfig.Entry> entries = new ArrayList<>();
    for (EffectiveServerConfig.Entry e : effective.entries()) {
      if (!options.changedOnly() || !e.isDefault()) {
        entries.add(e);
      }
    }
    if (options.json()) {
      ObjectNode node = CliOutput.result(options.command());
      node.put("count", entries.size());
      ArrayNode settings = node.putArray("settings");
      for (EffectiveServerConfig.Entry e : entries) {
        ObjectNode s = settings.addObject();
        s.put("key", e.key());
        s.put("value", e.value());
        s.put("source", e.source());
        s.put("default", e.isDefault());
      }
      CliOutput.print(node, out);
    } else {
      int width = entries.stream().mapToInt(e -> e.key().length()).max().orElse(3);
      String format = "%-" + width + "s  %s  [%s]%n";
      for (EffectiveServerConfig.Entry e : entries) {
        out.printf(Locale.ROOT, format, e.key(), e.value(), e.source());
      }
      out.printf(
          Locale.ROOT,
          "%d settings%s%n",
          entries.size(),
          options.changedOnly() ? " changed from the defaults" : "");
      out.flush();
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("settings", entries.size());
    after.put("changed_only", options.changedOnly());
    CliAudit.executed(properties, options.command(), AuditResult.success(), after);
    return AdminCli.EXIT_OK;
  }

  /** The findings of {@code config check}. */
  record Findings(List<String> errors, List<String> warnings) {}

  /** Everything the start would refuse ({@code errors}) or warn about ({@code warnings}). */
  static Findings findings(
      ArgosProperties properties,
      ConfigurableEnvironment environment,
      EffectiveServerConfig effective) {
    List<String> errors = new ArrayList<>(new ConfigValidation(properties, effective).violations());
    errors.addAll(CredentialPolicy.findings(properties));
    try {
      AuditSinks.parse(properties.audit().sinks());
    } catch (IllegalArgumentException e) {
      errors.add(e.getMessage());
    }
    List<String> warnings = new ArrayList<>();
    try {
      MasterKeyLoader.Loaded keys = MasterKeyLoader.load(properties, MasterKeyLoader.FILE_SYSTEM);
      warnings.addAll(keys.warnings());
      close(keys.current());
      close(keys.previous());
    } catch (MasterKeyLoader.MasterKeyException e) {
      errors.add(e.getMessage());
    }
    for (String key :
        new ConfigKeyWarnings(
                environment,
                properties,
                effective,
                new TestingHooks(properties, VersionCommand.build()))
            .unknownKeys()) {
      warnings.add("Unknown setting (ignored): " + key);
    }
    return new Findings(errors, warnings);
  }

  private static void close(MasterKey key) {
    if (key != null) {
      key.close();
    }
  }

  private static int check(
      Options options,
      ArgosProperties properties,
      ConfigurableEnvironment environment,
      EffectiveServerConfig effective,
      PrintStream out,
      PrintStream err) {
    Findings f = findings(properties, environment, effective);
    print(options, f, out);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("valid", f.errors().isEmpty());
    after.put("errors", f.errors().size());
    after.put("warnings", f.warnings().size());
    CliAudit.executed(
        properties,
        options.command(),
        f.errors().isEmpty() ? AuditResult.success() : AuditResult.failure("invalid_configuration"),
        after);
    if (!f.errors().isEmpty()) {
      err.println(
          "argos-admin config check: the configuration is invalid ("
              + f.errors().size()
              + " errors)");
      err.flush();
      return AdminCli.EXIT_FAILURE;
    }
    return AdminCli.EXIT_OK;
  }

  /**
   * {@code config check} when the configuration cannot even be bound (a value of the wrong type):
   * the check result with that one error, exit code 1. No audit row: without a bound configuration
   * there is no database to write it to.
   */
  static int bindingFailure(boolean json, String message, PrintStream out, PrintStream err) {
    print(new Options(true, false, json), new Findings(List.of(message), List.of()), out);
    err.println("argos-admin config check: the configuration cannot be bound: " + message);
    err.flush();
    return AdminCli.EXIT_FAILURE;
  }

  private static void print(Options options, Findings f, PrintStream out) {
    if (options.json()) {
      ObjectNode node = CliOutput.result(options.command());
      node.put("valid", f.errors().isEmpty());
      ArrayNode errors = node.putArray("errors");
      f.errors().forEach(errors::add);
      ArrayNode warnings = node.putArray("warnings");
      f.warnings().forEach(warnings::add);
      CliOutput.print(node, out);
      return;
    }
    out.println(
        f.errors().isEmpty()
            ? "Configuration valid."
            : "Configuration invalid: the server would refuse to start.");
    for (String e : f.errors()) {
      out.println("ERROR  " + e);
    }
    for (String w : f.warnings()) {
      out.println("WARN   " + w);
    }
    out.flush();
  }
}
