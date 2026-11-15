/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

/**
 * A data record does not fit under {@code argos.wal.max-bytes} (10 GiB by default, 12 §3.4). The
 * record was not written. The collector answers {@code ACK_OK} with {@code dropped_items} = every
 * item, leaves the deduplication window unchanged and counts {@code
 * argos_collector_dropped_total{reason="wal_full"}} (DSN-11-09, 11 §6, 12 §3.2): the only ack
 * without a preceding fsync.
 */
public final class WalFullException extends Exception {

  private static final long serialVersionUID = 1L;

  private final long bytesInUse;
  private final long maxBytes;

  /**
   * @param bytesInUse WAL bytes on disk when the record was refused
   * @param maxBytes the configured maximum
   */
  public WalFullException(long bytesInUse, long maxBytes) {
    super("WAL full: " + bytesInUse + " of " + maxBytes + " bytes in use");
    this.bytesInUse = bytesInUse;
    this.maxBytes = maxBytes;
  }

  /** WAL bytes on disk when the record was refused. */
  public long bytesInUse() {
    return bytesInUse;
  }

  /** The configured maximum ({@code argos.wal.max-bytes}). */
  public long maxBytes() {
    return maxBytes;
  }
}
