/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

/**
 * The 15 fixed latency buckets of {@code SqlStat.buckets} and {@code ExtStat.buckets} (DSN-13-03,
 * 13 §2 {@code LATENCY_BUCKETS_MS}), shared by the agent statistics aggregator, the server and the
 * query layer.
 *
 * <p>Bucket {@code i < 14} counts executions whose elapsed time in milliseconds is at most {@link
 * #upperBoundMs(int) upperBoundMs(i)} and above the previous bound; bucket 14 counts everything
 * above 30,000 ms. Upper bounds are inclusive.
 */
public final class LatencyBuckets {

  /** Number of buckets; every {@code buckets} field carries exactly this many counts. */
  public static final int COUNT = 15;

  private static final long[] UPPER_BOUNDS_MS = {
    1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 30_000
  };

  private LatencyBuckets() {}

  /**
   * The inclusive upper bound of bucket {@code index} in milliseconds, or {@link Long#MAX_VALUE}
   * for the last bucket.
   *
   * @throws IndexOutOfBoundsException if {@code index} is not in {@code [0, COUNT)}
   */
  public static long upperBoundMs(int index) {
    if (index < 0 || index >= COUNT) {
      throw new IndexOutOfBoundsException("bucket index " + index + " not in [0, " + COUNT + ")");
    }
    return index < UPPER_BOUNDS_MS.length ? UPPER_BOUNDS_MS[index] : Long.MAX_VALUE;
  }

  /** The bucket of an elapsed time in milliseconds (negative values count as 0 ms). */
  public static int indexOfMs(long elapsedMs) {
    for (int i = 0; i < UPPER_BOUNDS_MS.length; i++) {
      if (elapsedMs <= UPPER_BOUNDS_MS[i]) {
        return i;
      }
    }
    return COUNT - 1;
  }

  /**
   * The bucket of an elapsed time in microseconds, converted to milliseconds with the rounding the
   * server uses when it stores durations (11 §4.4: half up), so that a statistic's bucket matches
   * its stored {@code *_ms} value.
   */
  public static int indexOfMicros(long elapsedUs) {
    return indexOfMs(elapsedUs <= 0 ? 0 : (elapsedUs + 500) / 1_000);
  }
}
