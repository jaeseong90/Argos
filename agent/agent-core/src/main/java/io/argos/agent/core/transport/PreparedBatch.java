/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import com.google.protobuf.Message;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.StatBatch;
import java.util.Objects;

/**
 * A metric, statistics or active-snapshot batch that its producer built completely (WO-16: the
 * metric collector, the statistics aggregator and the active snapshotter put these into the {@code
 * metric}, {@code stats} and {@code active} queues of 10 §10.1). The sender only adds the {@code
 * BatchHeader} (instance, epoch, the kind's next {@code seq}, send time) and sends it as one RPC;
 * items of any other type in those queues are dropped and counted. The body's own {@code header}
 * field, if set, is replaced.
 */
public final class PreparedBatch implements QueueItem {
  private final BatchKind kind;
  private final Message body;
  private final long estimatedBytes;
  private final long createdNanos;

  private PreparedBatch(BatchKind kind, Message body, long createdNanos) {
    this.kind = kind;
    this.body = Objects.requireNonNull(body, "body");
    this.estimatedBytes = 64L + 2L * body.getSerializedSize();
    this.createdNanos = createdNanos;
  }

  /** A {@code SendMetrics} batch (5 s grid, 11 §3). */
  public static PreparedBatch metrics(MetricBatch body) {
    return new PreparedBatch(BatchKind.METRICS, body, System.nanoTime());
  }

  /** A {@code SendStats} batch (one 10 s window, D-12). */
  public static PreparedBatch stats(StatBatch body) {
    return new PreparedBatch(BatchKind.STATS, body, System.nanoTime());
  }

  /** A {@code SendActiveSnapshot} message (every 5 s, deadline 3 s). */
  public static PreparedBatch active(ActiveTransactionSnapshot body) {
    return new PreparedBatch(BatchKind.ACTIVE, body, System.nanoTime());
  }

  public BatchKind kind() {
    return kind;
  }

  /** The batch without its header. */
  public Message body() {
    return body;
  }

  @Override
  public long estimatedBytes() {
    return estimatedBytes;
  }

  @Override
  public long createdNanos() {
    return createdNanos;
  }
}
