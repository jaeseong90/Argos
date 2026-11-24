/* SPDX-License-Identifier: Apache-2.0 */
package example.logging;

/** The logging framework of the fixture: one logger and the framework's MDC. */
interface FixtureLog {
  /** The pattern of every framework's console appender. */
  String PATTERN = "fixture %X{argos.trace_id}|%X{argos.tx_id}|%X{user}|%m%n";

  void info(String message);

  void put(String key, String value);

  void remove(String key);

  void clear();
}
