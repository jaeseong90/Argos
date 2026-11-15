/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * {@link WalWriter#append} waited its whole append wait (1 s, 11 §10: "WAL fsync delay > 1 s" is
 * {@code UNAVAILABLE}) or was interrupted after the record had entered the writer queue. The record
 * is not known to be durable, but it is not failed either: it may still be written and fsynced
 * later. The caller must not ack, and must not treat the record as never written (a resend with the
 * same seq must not be stored a second time, D-13):
 *
 * <ul>
 *   <li>{@link #pending()} completes normally with the record's position once its group is fsynced
 *       (the record is then durable exactly as if {@code append} had returned that position);
 *   <li>it completes exceptionally ({@link WalWriteException}, {@link WalFullException}, possibly
 *       wrapped in a {@link java.util.concurrent.CompletionException}) when the record is not
 *       written.
 * </ul>
 *
 * The collector answers {@code UNAVAILABLE} with {@code argos-retry-after-ms}, keeps the batch's
 * deduplication claim until {@link #pending()} completes, and then marks (durable) or releases (not
 * written) it.
 */
public final class WalAppendPendingException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient CompletionStage<WalPosition> pending;

  /**
   * @param message why the caller stopped waiting, without collected data
   * @param pending completes with the record's position once it is durable, or exceptionally
   */
  public WalAppendPendingException(String message, CompletionStage<WalPosition> pending) {
    super(message);
    this.pending = Objects.requireNonNull(pending, "pending");
  }

  /**
   * The outcome of the record: its position once durable, or the reason it was not written. Never
   * completed by the caller. {@code null} only after Java serialization (never used).
   */
  public CompletionStage<WalPosition> pending() {
    return pending;
  }
}
