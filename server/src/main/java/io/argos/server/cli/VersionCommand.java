/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.config.ServerBuild;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin version [--json]} (17 §4.4): the server version, the commit it was built from
 * and the agent protocol version (the jar's {@code META-INF/build-info.properties}), with the Java
 * runtime and operating system the command runs on. It needs no configuration and no database.
 *
 * <p>No audit row: the command reads the jar's own build information and changes nothing, reveals
 * nothing that {@code /actuator/info} and the start log do not show, and must work on a host
 * without database access (an upgrade checks the version before migrating). Every other subcommand
 * records an audit row (DSN-17-14).
 */
final class VersionCommand {

  static final String USAGE =
      """
      Usage: argos-admin version [--json]
        Prints the server version, commit and agent protocol version of this jar.
        Exit codes: 0 success, 2 invalid arguments.
      """;

  static final String COMMAND = "version";

  private VersionCommand() {}

  /** Parses the options; only {@code --json}. */
  static boolean parse(List<String> args) throws CliUsageException {
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      if (a.name().equals("--json")) {
        a.noValue();
        json = true;
      } else {
        throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    return json;
  }

  /** The build of this jar ({@link ServerBuild#UNKNOWN} values without build information). */
  static ServerBuild build() {
    Properties p = new Properties();
    try (InputStream in =
        VersionCommand.class
            .getClassLoader()
            .getResourceAsStream("META-INF/build-info.properties")) {
      if (in != null) {
        p.load(in);
      }
    } catch (IOException e) {
      // unknown values below
    }
    return new ServerBuild(
        p.getProperty("build.version"),
        p.getProperty("build.commit"),
        p.getProperty("build.protocol"));
  }

  /** Prints the version. */
  static int execute(boolean json, PrintStream out) {
    ServerBuild b = build();
    String java = System.getProperty("java.version", "?");
    String vendor = System.getProperty("java.vendor", "?");
    String os =
        System.getProperty("os.name", "?")
            + " "
            + System.getProperty("os.version", "?")
            + " "
            + System.getProperty("os.arch", "?");
    if (json) {
      ObjectNode node = CliOutput.result(COMMAND);
      node.put("version", b.version());
      node.put("commit", b.commit());
      node.put("protocol", b.protocol());
      node.put("release", b.isRelease());
      node.put("java_version", java);
      node.put("java_vendor", vendor);
      node.put("os", os);
      CliOutput.print(node, out);
    } else {
      out.printf(
          Locale.ROOT,
          "Argos server %s (commit %s, agent protocol %s)%nJava %s (%s), %s%n",
          b.version(),
          b.commit(),
          b.protocol(),
          java,
          vendor,
          os);
      out.flush();
    }
    return AdminCli.EXIT_OK;
  }
}
