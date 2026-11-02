/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.io.IOException;

/** Destination of formatted log lines; used by the logger thread only (not thread-safe). */
interface LogSink {
  /** Writes one line ({@code line} has no line terminator); {@code record} is its source event. */
  void write(LogRecord record, String line) throws IOException;

  void flush() throws IOException;

  void close();

  /** Short description for the start banner ("file /var/log/argos/argos-agent.log", ...). */
  String describe();
}
