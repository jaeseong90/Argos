/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import java.time.Instant;
import java.util.Objects;

/**
 * One part of a time range read from one storage level: the half-open interval {@code [from, to)}
 * (14 §2.3). A segmented query ({@link SegmentedQuery}) binds {@code from} and {@code to} of each
 * segment to its own fragment.
 *
 * @param level the storage level of this part
 * @param from first instant (inclusive); minute aligned for {@link TableLevel#MINUTE}, hour aligned
 *     for {@link TableLevel#HOUR}
 * @param to end (exclusive), aligned like {@code from}
 */
public record TimeSegment(TableLevel level, Instant from, Instant to) {

  public TimeSegment {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (!to.isAfter(from)) {
      throw new IllegalArgumentException("Empty segment " + from + " .. " + to);
    }
    long unit = level.granularity().toMillis();
    if (level != TableLevel.RAW
        && (from.toEpochMilli() % unit != 0 || to.toEpochMilli() % unit != 0)) {
      throw new IllegalArgumentException(
          "A " + level.label() + " segment must be aligned: " + from + " .. " + to);
    }
  }
}
