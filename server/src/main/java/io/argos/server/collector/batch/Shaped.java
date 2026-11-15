/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

import java.util.Objects;

/**
 * A batch after one shaping step and the number of items the step removed, counted in the item unit
 * of {@code Ack.dropped_items} ({@link AckItems}).
 *
 * @param batch the remaining batch (the same instance when nothing was removed)
 * @param droppedItems items removed by this step
 */
public record Shaped<T>(T batch, int droppedItems) {

  /** Validates the components. */
  public Shaped {
    Objects.requireNonNull(batch, "batch");
    if (droppedItems < 0) {
      throw new IllegalArgumentException("droppedItems < 0");
    }
  }

  /** {@code batch} unchanged. */
  public static <T> Shaped<T> unchanged(T batch) {
    return new Shaped<>(batch, 0);
  }
}
