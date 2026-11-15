/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

/**
 * {@link WalWriter#append} refused a data record at once because the WAL is not keeping up: a group
 * write and fsync has already been running for longer than the append wait (1 s, 11 §10: "WAL fsync
 * delay > 1 s" is {@code UNAVAILABLE}), or the data records waiting for the writer would exceed
 * their byte limit. Nothing was queued and the record will never be written by this call: the
 * caller must not ack, may treat the batch as never written, and retries later. The collector
 * releases the batch's deduplication claim and answers {@code UNAVAILABLE} with {@code
 * argos-retry-after-ms}; the agent's resend with the same seq is new.
 *
 * <p>Unlike {@link WalWriteException} it is expected back-pressure, not an I/O failure: it carries
 * no cause and no stack trace (it may be thrown for every batch while a disk is stuck).
 */
public final class WalBusyException extends Exception {

  private static final long serialVersionUID = 1L;

  /**
   * @param message why the record was refused, without collected data
   */
  public WalBusyException(String message) {
    super(message, null, false, false);
  }
}
