/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import io.argos.server.storage.query.TableLevel;
import io.argos.server.storage.query.TimeSegment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The storage plan of one time-range request ({@link SourceSelector#plan}).
 *
 * @param from range start, as requested
 * @param to range end (exclusive), as requested
 * @param level the base level chosen for the range (14 §3: up to 6 h raw, up to 7 d minute rollups,
 *     beyond hour rollups; coarser when the range is older than a retention)
 * @param source the {@code meta.source} of the response: the level of every segment, {@code mixed}
 *     when they differ
 * @param step the bucket step
 * @param firstBucket start of the first bucket: {@code from} floored to the step (epoch aligned,
 *     the {@code toStartOfInterval} grid of ClickHouse)
 * @param buckets number of buckets covering {@code [from, to)}, at most 720
 * @param segments the parts of the range by level, contiguous, in time order; each segment's bounds
 *     are aligned to its level
 * @param retentionExceeded whether part of the range is older than the raw retention of the family,
 *     on every level: raw rows are gone there, a whole rollup minute (or hour) may have replaced a
 *     requested edge, and older data may be gone too (D-03, 14 §2.5); false for a forced level
 */
public record SourcePlan(
    Instant from,
    Instant to,
    TableLevel level,
    Source source,
    Duration step,
    Instant firstBucket,
    int buckets,
    List<TimeSegment> segments,
    boolean retentionExceeded) {

  public SourcePlan {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(step, "step");
    Objects.requireNonNull(firstBucket, "firstBucket");
    segments = List.copyOf(segments);
  }

  /** The start of every bucket, in order ({@code data.series.ts} of a time series). */
  public List<Instant> bucketStarts() {
    Instant[] starts = new Instant[buckets];
    for (int i = 0; i < buckets; i++) {
      starts[i] = firstBucket.plus(step.multipliedBy(i));
    }
    return List.of(starts);
  }

  /** The bucket index of a bucket start returned by ClickHouse, or -1 outside the grid. */
  public int bucketIndex(Instant bucketStart) {
    long offset = bucketStart.toEpochMilli() - firstBucket.toEpochMilli();
    long stepMs = step.toMillis();
    if (offset < 0 || offset % stepMs != 0) {
      return -1;
    }
    long i = offset / stepMs;
    return i < buckets ? (int) i : -1;
  }

  /** The step in whole seconds (the {@code step_seconds} parameter of series templates). */
  public long stepSeconds() {
    return step.toSeconds();
  }
}
