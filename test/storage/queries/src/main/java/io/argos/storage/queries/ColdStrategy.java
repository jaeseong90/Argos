/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * How the runner makes the cold run of a variant cold (18 §9: OS page cache drop + {@code SYSTEM
 * DROP MARK CACHE}, then one run). None of the three Argos accounts may run {@code SYSTEM DROP ...
 * CACHE} (SEC-76), so the caches are emptied from outside:
 *
 * <ul>
 *   <li>{@code none}: the cold run is simply the first run of the variant (caches warm from earlier
 *       queries); reported as such.
 *   <li>{@code restart}: the server is restarted on its data volume (mark cache, uncompressed and
 *       query caches, loaded primary keys all empty); the OS page cache stays.
 *   <li>{@code restart+dropcache}: restart and a privileged helper container drops the page cache
 *       of the Docker host (affects every container on the host).
 *   <li>{@code command:<shell>}: an operator command, e.g. on the dedicated argos-storage host
 *       {@code sync; echo 3 > /proc/sys/vm/drop_caches; clickhouse-client -q 'SYSTEM DROP MARK
 *       CACHE'} with an operator account.
 * </ul>
 *
 * @param mode the mode
 * @param command the shell command of {@code command:} (empty otherwise)
 */
public record ColdStrategy(Mode mode, String command) {

  /** Modes. */
  public enum Mode {
    /** First run, caches not emptied. */
    NONE,
    /** Server restart on its data volume. */
    RESTART,
    /** Restart and host page cache drop. */
    RESTART_DROP_OS_CACHE,
    /** Operator shell command. */
    COMMAND
  }

  /** The default: no cache emptying. */
  public static final ColdStrategy NONE = new ColdStrategy(Mode.NONE, "");

  /** Parses {@code none}, {@code restart}, {@code restart+dropcache} or {@code command:<shell>}. */
  public static ColdStrategy parse(String text) {
    if (text.startsWith("command:")) {
      return new ColdStrategy(Mode.COMMAND, text.substring("command:".length()));
    }
    return switch (text) {
      case "none" -> NONE;
      case "restart" -> new ColdStrategy(Mode.RESTART, "");
      case "restart+dropcache" -> new ColdStrategy(Mode.RESTART_DROP_OS_CACHE, "");
      default -> throw new IllegalArgumentException("Unknown cold strategy: " + text);
    };
  }

  /** Empties the caches before a cold run. */
  public void prepare(Server server) {
    switch (mode) {
      case NONE -> {}
      case RESTART -> server.restart();
      case RESTART_DROP_OS_CACHE -> {
        server.restart();
        server.dropOsPageCache();
      }
      case COMMAND -> run(command);
    }
  }

  /** Report text. */
  public String describe() {
    return switch (mode) {
      case NONE -> "none (cold = first run; caches not emptied)";
      case RESTART -> "server restart on its data volume before each cold run (OS page cache kept)";
      case RESTART_DROP_OS_CACHE ->
          "server restart + Docker host page cache drop before each cold run";
      case COMMAND -> "operator command before each cold run: " + command;
    };
  }

  private static void run(String command) {
    try {
      Process p =
          new ProcessBuilder(List.of("sh", "-c", command)).redirectErrorStream(true).start();
      String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
        throw new IllegalStateException("Cold command failed: " + command + "\n" + output);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
