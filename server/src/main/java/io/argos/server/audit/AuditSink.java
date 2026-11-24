/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

/**
 * Where audit records go (12 §8.1 {@code argos.audit.sinks}): the PostgreSQL sink ({@code
 * io.argos.server.storage.postgres.audit.PostgresAuditSink}, mandatory and {@linkplain #primary()
 * primary}), the JSON Lines sink ({@link JsonlAuditSink}) and the syslog sink ({@link
 * SyslogAuditSink}). A record the primary sink could not write goes to the fallback file (16
 * §11.3).
 */
public interface AuditSink {

  /**
   * Writes one record.
   *
   * @throws RuntimeException when the record could not be written (counted by the caller)
   */
  void write(AuditRecord record);

  /**
   * Whether this is the store of record ({@code audit_logs}): its failures send the record to the
   * fallback file.
   */
  default boolean primary() {
    return false;
  }

  /** Releases files, sockets and threads (server shutdown); idempotent. */
  default void close() {}
}
