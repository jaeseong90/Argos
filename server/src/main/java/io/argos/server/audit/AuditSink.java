/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

/**
 * Where audit records go (12 §8.1 {@code argos.audit.sinks}). WO-24 provides the PostgreSQL sink
 * ({@code io.argos.server.storage.postgres.audit.PostgresAuditSink}, the mandatory one); syslog and
 * JSON Lines sinks and the write-failure fallback file are WO-28's.
 */
public interface AuditSink {

  /**
   * Writes one record.
   *
   * @throws RuntimeException when the record could not be written (counted by the caller)
   */
  void write(AuditRecord record);
}
