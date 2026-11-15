/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.Transaction;
import java.util.List;

/**
 * Destination of what the queues hold (exporter SPI, WO-02, 18 §5.4): ended transactions (the XLog
 * records of the transaction queue) and the profiles of sampled transactions (the profile queue).
 * Built-in: {@code noop}/{@code drop} (benchmarks), {@code file} (JSONL, tests). The {@code grpc}
 * slot is filled by the transport ({@code io.argos.agent.core.transport.GrpcExporterFactory},
 * WO-11).
 *
 * <p>Threading: only the {@code argos-sender} thread calls an exporter, so implementations need no
 * synchronisation and may block on I/O (with timeouts); application threads never wait for them.
 * Exceptions are logged by the caller and the batch is dropped.
 */
public interface Exporter {
  /** Exports ended transactions, oldest first. */
  void export(List<Transaction> batch) throws Exception;

  /**
   * Exports the profiles of sampled transactions, oldest first (11 {@code ProfileBatch}). The
   * default drops them (exporters that only write transaction records).
   */
  default void exportProfiles(List<ProfileRecord> batch) throws Exception {}

  /** Writes buffered data (called after each drain of the queues and at shutdown). */
  void flush() throws Exception;

  /** Releases resources (shutdown hook, at most about one second). */
  void close();

  /** Short description for the start banner ({@code "file /x/tx.jsonl"}, {@code "noop"}). */
  String describe();
}
