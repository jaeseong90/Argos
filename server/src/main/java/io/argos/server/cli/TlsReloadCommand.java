/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.AuditResult;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.tls.CertReloader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin tls reload [--wait <seconds>] [--json]} (17 §8, SEC-09, 16 §3.3): makes the
 * running server load the gRPC listener's certificate files again, without a restart.
 *
 * <ol>
 *   <li>The files of {@code argos.collector.tls.*} are loaded here first, as the listener would
 *       ({@link CertReloader#inspect}); invalid files stop the command (exit 1) and the server is
 *       not asked.
 *   <li>The command writes the request file {@code <data>/tls/reload.request} ({@link
 *       CertReloader#requestFile}); the server checks it every 5 seconds, reloads (keeping the old
 *       context when the files fail; {@code TLS_RELOADED} in the audit log) and writes {@code
 *       reload.result}.
 *   <li>It waits up to {@code --wait} seconds (default {@value #DEFAULT_WAIT_SECONDS}, 0 = do not
 *       wait) for that result. Without a result (no server running on this data directory) the
 *       request stays and the next start loads the files anyway: exit 0 with {@code
 *       "server_result": null}.
 * </ol>
 *
 * Output: the certificate (subject, serial, expiry) and the server's result; JSON {@code
 * {"command", "listener", "certificate": {"subject", "serial", "not_after"}, "requested",
 * "server_result": {"reloaded", "serial", "not_after", "error"} | null}}. No key material is
 * printed. Exit codes 0 (files valid; reloaded, or no answer), 1 (invalid files, or the server
 * could not reload), 2 invalid arguments. Audit: {@code ADMIN_COMMAND_EXECUTED}.
 */
final class TlsReloadCommand {

  static final String USAGE =
      """
      Usage: argos-admin tls reload [--wait <seconds>] [--json]
        Checks the certificate files of argos.collector.tls.* and asks the running server to load
        them (request file <data>/tls/reload.request), then waits up to --wait seconds (default
        15, 0 = no wait) for its answer.
        Exit codes: 0 success, 1 invalid files or reload failed, 2 invalid arguments.
      """;

  static final String COMMAND = "tls reload";

  /** Default wait for the server's answer (it checks every 5 seconds). */
  static final int DEFAULT_WAIT_SECONDS = 15;

  /**
   * The parsed options.
   *
   * @param waitSeconds 0 to 300
   * @param json {@code --json}
   */
  record Options(int waitSeconds, boolean json) {}

  private TlsReloadCommand() {}

  /** Parses the options after {@code tls reload}. */
  static Options parse(List<String> args) throws CliUsageException {
    Integer wait = null;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      switch (a.name()) {
        case "--wait" -> {
          if (wait != null) {
            throw new CliUsageException("--wait given twice");
          }
          String v = a.value();
          try {
            wait = Integer.parseInt(v);
          } catch (NumberFormatException e) {
            wait = -1;
          }
          if (wait < 0 || wait > 300) {
            throw new CliUsageException(
                "--wait must be from 0 to 300 seconds, got: " + CliArgs.shorten(v));
          }
        }
        case "--json" -> {
          a.noValue();
          json = true;
        }
        default -> throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    return new Options(wait == null ? DEFAULT_WAIT_SECONDS : wait, json);
  }

  /** Checks the files, writes the request and waits for the answer. */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    ArgosProperties.Collector.Tls tls = properties.collector().tls();
    CertReloader.CertificateInfo info;
    try {
      info = CertReloader.inspect(tls);
    } catch (RuntimeException e) {
      String message =
          "The certificate files of argos.collector.tls cannot be loaded: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : oneLine(e.getMessage()));
      CliAudit.executed(properties, COMMAND, AuditResult.failure("invalid_files"), null);
      return CliOutput.fail(
          options.json(), COMMAND, message, "invalid_files", out, err, AdminCli.EXIT_FAILURE);
    }
    Path request = CertReloader.requestFile(properties);
    Path result = CertReloader.resultFile(properties);
    Instant requestedAt = Instant.now();
    try {
      Files.createDirectories(request.getParent());
      Files.deleteIfExists(result);
      Files.writeString(request, requestedAt + "\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      String message =
          "Cannot write the request file " + request + ": " + e.getClass().getSimpleName();
      CliAudit.executed(properties, COMMAND, AuditResult.failure("request_file"), null);
      return CliOutput.fail(
          options.json(), COMMAND, message, "request_file", out, err, AdminCli.EXIT_FAILURE);
    }
    JsonNode answer = await(result, Duration.ofSeconds(options.waitSeconds()));
    boolean reloaded = answer != null && answer.path("reloaded").asBoolean(false);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("listener", info.listener());
    after.put("serial", info.serial());
    after.put("not_after", info.notAfter().toString());
    after.put("server_answered", answer != null);
    after.put("reloaded", reloaded);
    boolean failed = answer != null && !reloaded;
    CliAudit.executed(
        properties,
        COMMAND,
        failed
            ? AuditResult.failure("reload_failed")
            : AuditResult.success(answer == null ? "requested" : null),
        after);
    if (options.json()) {
      ObjectNode node = CliOutput.result(COMMAND);
      node.put("listener", info.listener());
      ObjectNode cert = node.putObject("certificate");
      cert.put("subject", info.subject());
      cert.put("serial", info.serial());
      cert.put("not_after", info.notAfter().toString());
      node.put("requested", true);
      node.put("request_file", request.toString());
      if (answer == null) {
        node.putNull("server_result");
      } else {
        ObjectNode r = node.putObject("server_result");
        r.put("reloaded", reloaded);
        copy(answer, r, "serial");
        copy(answer, r, "not_after");
        copy(answer, r, "error");
        copy(answer, r, "at");
      }
      CliOutput.print(node, out);
    } else {
      out.printf(
          Locale.ROOT,
          "Certificate files valid: %s, serial %s, expires %s.%n",
          info.subject(),
          info.serial(),
          info.notAfter());
      if (answer == null) {
        out.printf(
            Locale.ROOT,
            "Reload requested (%s); no answer from a running server within %d seconds. A server"
                + " started later loads the files at start.%n",
            request,
            options.waitSeconds());
      } else if (reloaded) {
        out.printf(
            Locale.ROOT,
            "The server reloaded: new handshakes use serial %s.%n",
            answer.path("serial").asString(""));
      } else {
        out.printf(
            Locale.ROOT,
            "The server could not reload and keeps its previous certificate: %s%n",
            answer.path("error").asString("unknown error"));
      }
      out.flush();
    }
    if (failed) {
      err.println("argos-admin tls reload: the server could not reload the certificate files");
      err.flush();
      return AdminCli.EXIT_FAILURE;
    }
    return AdminCli.EXIT_OK;
  }

  private static void copy(JsonNode from, ObjectNode to, String field) {
    JsonNode v = from.get(field);
    if (v != null && !v.isNull()) {
      to.put(field, v.asString());
    }
  }

  /** The server's result file, read when it appears within {@code wait}; null otherwise. */
  private static @Nullable JsonNode await(Path result, Duration wait) {
    long deadline = System.nanoTime() + wait.toNanos();
    while (true) {
      if (Files.isRegularFile(result)) {
        try {
          String text = Files.readString(result, StandardCharsets.UTF_8);
          if (!text.isBlank()) {
            return CliOutput.JSON.readTree(text);
          }
        } catch (IOException | RuntimeException e) {
          // being written; read again
        }
      }
      if (System.nanoTime() >= deadline) {
        return null;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
  }

  private static String oneLine(String text) {
    String t = text.replaceAll("[\\r\\n\\t]+", " ").strip();
    return t.length() > 300 ? t.substring(0, 297) + "..." : t;
  }
}
