/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

/** One log event, formatted into a line on the logger thread. */
final class LogRecord {
  final long epochMillis;
  final LogLevel level;
  final String thread;
  final String message;
  final Throwable error;

  /** Estimated bytes counted against the pipeline's byte bound (0 for records never queued). */
  final long bytes;

  LogRecord(long epochMillis, LogLevel level, String thread, String message, Throwable error) {
    this(epochMillis, level, thread, message, error, 0L);
  }

  LogRecord(
      long epochMillis,
      LogLevel level,
      String thread,
      String message,
      Throwable error,
      long bytes) {
    this.epochMillis = epochMillis;
    this.level = level;
    this.thread = thread;
    this.message = message;
    this.error = error;
    this.bytes = bytes;
  }
}
