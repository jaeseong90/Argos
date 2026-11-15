/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import io.argos.proto.v1.Throttle;
import io.argos.server.wal.api.WalWriter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * The back-pressure hint of an ack (12 §3.2 {@code withThrottle(throttle.hint(s))}, 11 §6 "백프레셔"):
 * when the WAL is above {@code argos.wal.warn-ratio} (0.8) of its maximum, or the fullest
 * ClickHouse writer queue above {@link #WRITER_WARN_RATIO} of its capacity, acks carry {@code
 * Throttle.send_backoff_ms}: {@value #MIN_BACKOFF_MS} ms at the threshold rising linearly to
 * {@value #MAX_BACKOFF_MS} ms when full. The agent pauses that long before its next batch; its
 * queue keeps filling, so nothing is lost by the hint itself.
 */
public final class ThrottleAdvisor {

  /** Writer queue share above which acks carry a hint (the WAL default warn ratio). */
  public static final double WRITER_WARN_RATIO = 0.8;

  /** Backoff at the threshold. */
  public static final int MIN_BACKOFF_MS = 250;

  /** Backoff when the WAL or a queue is full. */
  public static final int MAX_BACKOFF_MS = 5_000;

  private final WalWriter wal;
  private final DoubleSupplier writerQueueRatio;
  private final double walWarnRatio;

  /**
   * @param wal the WAL ({@link WalWriter#usageRatio()})
   * @param writerQueueRatio the fullest writer queue's share of its capacity (0 when unknown)
   * @param walWarnRatio {@code argos.wal.warn-ratio}
   */
  public ThrottleAdvisor(WalWriter wal, DoubleSupplier writerQueueRatio, double walWarnRatio) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.writerQueueRatio = Objects.requireNonNull(writerQueueRatio, "writerQueueRatio");
    this.walWarnRatio = walWarnRatio;
  }

  /** The hint to put into an ack now, if any. */
  public Optional<Throttle> hint() {
    double over =
        Math.max(
            excess(wal.usageRatio(), walWarnRatio),
            excess(writerQueueRatio.getAsDouble(), WRITER_WARN_RATIO));
    if (over <= 0) {
      return Optional.empty();
    }
    int backoff = (int) Math.round(MIN_BACKOFF_MS + over * (MAX_BACKOFF_MS - MIN_BACKOFF_MS));
    return Optional.of(Throttle.newBuilder().setSendBackoffMs(backoff).build());
  }

  /** How far {@code ratio} is past {@code warn}, 0 (at or below) to 1 (full). */
  static double excess(double ratio, double warn) {
    if (Double.isNaN(ratio) || ratio <= warn) {
      return 0;
    }
    if (warn >= 1) {
      return 1;
    }
    return Math.min(1.0, (ratio - warn) / (1 - warn));
  }
}
