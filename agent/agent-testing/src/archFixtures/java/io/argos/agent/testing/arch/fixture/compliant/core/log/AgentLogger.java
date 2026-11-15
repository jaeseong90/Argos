/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.log;

/** The agent's own logger (10 §14): the calls LOG-01 checks. */
public final class AgentLogger {
  private static int calls;

  private AgentLogger() {}

  public static void error(String format, Object... args) {
    calls++;
  }

  public static void warn(String format, Object... args) {
    calls++;
  }

  public static void info(String format, Object... args) {
    calls++;
  }

  public static void debug(String format, Object... args) {
    calls++;
  }

  public static void warnOnce(String key, String format, Object... args) {
    calls++;
  }

  public static int calls() {
    return calls;
  }
}
