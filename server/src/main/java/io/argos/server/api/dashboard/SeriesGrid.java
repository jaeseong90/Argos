/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.api.query.SourcePlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The time grid of a series response (14 §2.4, §4.4): the bucket starts of a {@link SourcePlan},
 * and for each bucket the seconds it covers inside the requested range and before now (the
 * denominator of a rate: the first and last bucket of an unaligned range and the current bucket are
 * partial). A bucket that starts after now has no data yet: its values are null (D-23), and so are
 * the values of a bucket ClickHouse returned no row for, unless the caller knows the value is 0 (a
 * count of transactions in a covered bucket).
 */
public final class SeriesGrid {

  private final SourcePlan plan;
  private final Instant now;
  private final List<Instant> starts;

  /** The grid of {@code plan}, with {@code now} for the partial and future buckets. */
  public SeriesGrid(SourcePlan plan, Instant now) {
    this.plan = plan;
    this.now = now;
    this.starts = plan.bucketStarts();
  }

  /** The plan. */
  public SourcePlan plan() {
    return plan;
  }

  /** The number of buckets. */
  public int size() {
    return starts.size();
  }

  /** The bucket starts ({@code series.ts}). */
  public List<Instant> ts() {
    return starts;
  }

  /** The index of a bucket start returned by ClickHouse, or -1 outside the grid. */
  public int index(Instant bucketStart) {
    return bucketStart == null ? -1 : plan.bucketIndex(bucketStart);
  }

  /**
   * The seconds of bucket {@code i} inside {@code [from, min(to, now))}: the denominator of a rate;
   * 0 for a bucket entirely in the future.
   */
  public double seconds(int i) {
    long start = Math.max(starts.get(i).toEpochMilli(), plan.from().toEpochMilli());
    long end =
        Math.min(
            starts.get(i).plus(plan.step()).toEpochMilli(),
            Math.min(plan.to().toEpochMilli(), now.toEpochMilli()));
    return end > start ? (end - start) / 1000.0 : 0;
  }

  /** Whether bucket {@code i} starts at or after now (no data can exist yet). */
  public boolean future(int i) {
    return !starts.get(i).isBefore(now);
  }

  /** The seconds of the whole range before now ({@code [from, min(to, now))}). */
  public double rangeSeconds() {
    long end = Math.min(plan.to().toEpochMilli(), now.toEpochMilli());
    long start = plan.from().toEpochMilli();
    return end > start ? (end - start) / 1000.0 : 0;
  }

  /**
   * The bucket of {@code other} that contains bucket {@code i} of this grid (another family read
   * with a coarser step, e.g. metrics older than their raw retention), or -1.
   */
  public int indexIn(SourcePlan other, int i) {
    long stepMs = other.step().toMillis();
    long t = starts.get(i).toEpochMilli();
    return other.bucketIndex(Instant.ofEpochMilli(Math.floorDiv(t, stepMs) * stepMs));
  }

  /** A list of {@code n} nulls (a series without a source). */
  public static <T> List<T> nulls(int n) {
    return new ArrayList<>(Collections.nCopies(n, null));
  }
}
