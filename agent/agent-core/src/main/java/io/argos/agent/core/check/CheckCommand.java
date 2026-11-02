/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import io.argos.agent.core.export.AgentInfo;
import java.io.File;
import java.io.PrintStream;

/**
 * {@code java -jar argos-agent.jar --check|--version} (10 §13.3, F-A27). WO-02 implements item 6
 * (JDK version against the 8u252 minimum for TLS ALPN, U-4) and the version line; items 1-5, 7 and
 * 8 (configuration, token, connection, Hello, clock, class path, log directory) arrive with WO-19.
 * This class (and {@code core.log}'s fallback) is the only agent code allowed to print (AGT-02).
 */
public final class CheckCommand {
  static final int EXIT_OK = 0;
  static final int EXIT_FAIL = 1;
  static final int EXIT_USAGE = 2;

  private CheckCommand() {}

  /** Entry point used by {@code AgentBootstrap.check}; returns the process exit code. */
  public static int run(String[] args, File agentJar) {
    return run(args, agentJar, System.out, System.getProperty("java.version"));
  }

  static int run(String[] args, File agentJar, PrintStream out, String javaVersion) {
    AgentInfo info = AgentInfo.fromJar(agentJar);
    String versionLine =
        "Argos agent "
            + info.version()
            + " (commit "
            + info.commit()
            + ", protocol "
            + info.protocolVersion()
            + ")";
    if (args.length == 1 && args[0].equals("--version")) {
      out.println(versionLine);
      return EXIT_OK;
    }
    if (args.length < 1 || !args[0].equals("--check")) {
      out.println("usage: java -jar argos-agent.jar --check | --version");
      return EXIT_USAGE;
    }
    out.println(versionLine);
    boolean supported = jdkSupported(javaVersion);
    out.println(
        (supported ? "[PASS] " : "[FAIL] ")
            + "JDK "
            + javaVersion
            + " ("
            + System.getProperty("java.vendor")
            + "): "
            + (supported
                ? "supported"
                : "Java 8 update 252 or later is required (TLS ALPN for the collector connection)"));
    out.println(
        "[WARN] configuration, token, connection, clock, class path and log directory checks are"
            + " not available in this build (WO-19)");
    return supported ? EXIT_OK : EXIT_FAIL;
  }

  /**
   * True for Java 9+ and for Java 8 update 252 or later ({@code 1.8.0_252}). Unparseable versions
   * are treated as supported (newer version schemes).
   */
  static boolean jdkSupported(String javaVersion) {
    if (javaVersion == null || !javaVersion.startsWith("1.")) {
      return true;
    }
    if (!javaVersion.startsWith("1.8.0")) {
      return false; // Java 7 and older
    }
    int underscore = javaVersion.indexOf('_');
    if (underscore < 0) {
      return false;
    }
    int end = underscore + 1;
    while (end < javaVersion.length() && Character.isDigit(javaVersion.charAt(end))) {
      end++;
    }
    try {
      return Integer.parseInt(javaVersion.substring(underscore + 1, end)) >= 252;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
