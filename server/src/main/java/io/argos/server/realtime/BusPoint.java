/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

/**
 * One XLog point on the bus: the stored values of the ingest port's {@link
 * io.argos.server.ingest.api.XlogPoint} plus the instance. The push column {@code status} is
 * derived with {@link #status(int)} from the application's slow threshold when a push is built.
 *
 * @param txId {@code tx_id} (unsigned 64 bits)
 * @param tsMs stored {@code ts}, epoch milliseconds
 * @param elapsedMs {@code elapsed_ms}
 * @param error {@code error}
 * @param instanceId {@code instance_id}
 * @param serviceId {@code service_id} (unsigned 64 bits)
 * @param kind the ClickHouse {@code kind} number: 1 HTTP, 2 BATCH, 3 MESSAGE
 */
public record BusPoint(
    long txId, long tsMs, int elapsedMs, boolean error, int instanceId, long serviceId, int kind) {

  /** Status of a normal point. */
  public static final int NORMAL = 0;

  /** Status of a slow point. */
  public static final int SLOW = 1;

  /** Status of an error point. */
  public static final int ERROR = 2;

  /**
   * The push column {@code status} (14 §4.5, the rule of the REST XLog in {@code
   * api.xlog.XlogService}): 2 error, else 1 when {@code elapsed_ms >= slow_threshold_ms}, else 0.
   */
  public int status(int slowThresholdMs) {
    return error ? ERROR : (elapsedMs >= slowThresholdMs ? SLOW : NORMAL);
  }
}
