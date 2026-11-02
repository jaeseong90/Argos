/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.trace.Transaction;
import java.util.List;

/**
 * Destination of finished transactions (exporter SPI, WO-02). Built-in: {@code noop} (records and
 * drops: the P-2 overhead baseline), {@code file} (JSONL, tests and PoC). The {@code grpc} slot is
 * filled by the transport ({@code io.argos.agent.core.transport.GrpcExporterFactory}, P-3/WO-11).
 *
 * <p>Threading: only the {@code argos-sender} thread calls an exporter, so implementations need no
 * synchronisation and may block on I/O (with timeouts); application threads never wait for them.
 * Exceptions are logged by the caller and the batch is dropped.
 */
public interface Exporter {
  /** Exports ended transactions, oldest first. */
  void export(List<Transaction> batch) throws Exception;

  /** Writes buffered data (called after each drain of the queue and at shutdown). */
  void flush() throws Exception;

  /** Releases resources (shutdown hook, at most about one second). */
  void close();

  /** Short description for the start banner ({@code "file /x/tx.jsonl"}, {@code "noop"}). */
  String describe();
}
