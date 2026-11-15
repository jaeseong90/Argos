/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

/**
 * A record could not be made durable: an I/O error of the write or the fsync, a writer that is
 * stopping, or a waiting thread that was interrupted (its interrupt flag is restored). Whether the
 * bytes reached the disk is unknown, so the caller must not ack: the collector answers {@code
 * UNAVAILABLE} and the agent retries with the same seq (11 §6, §10). A record that did reach the
 * disk is then deduplicated after a restart (the recovered window, C-3) or by its block token
 * (C-61).
 */
public final class WalWriteException extends Exception {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what failed, without collected data
   * @param cause the I/O error, or {@code null}
   */
  public WalWriteException(String message, Throwable cause) {
    super(message, cause);
  }
}
