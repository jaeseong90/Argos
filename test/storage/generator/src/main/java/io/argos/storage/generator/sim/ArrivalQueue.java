/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.sim;

import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * Rows waiting for their server receive time, bucketed by simulation tick ({@code tick-ms}, 1 s by
 * default) in a ring (arrival delays are bounded: elapsed time up to 30 s, statistics flush 11 s,
 * late batches up to 60 min). Release is in receive-bucket order and, within a bucket, in insertion
 * order, which keeps the output deterministic.
 */
final class ArrivalQueue {

  /** Ring span in seconds; must exceed the largest arrival delay. */
  static final int SECONDS = 8192;

  private record Pending(Table table, Row row, long receivedMs) {}

  private final long bucketMs;
  private final int buckets;
  private final List<List<Pending>> ring;
  private long next; // first bucket not released yet
  private long size;

  /**
   * @param startMs first instant of the simulation (rows received earlier go out first)
   * @param bucketMs bucket width (the simulation tick), divides 1000
   */
  ArrivalQueue(long startMs, long bucketMs) {
    this.bucketMs = bucketMs;
    this.buckets = (int) (SECONDS * 1000L / bucketMs);
    this.ring = new ArrayList<>(buckets);
    for (int i = 0; i < buckets; i++) {
      ring.add(null); // allocated on first use
    }
    this.next = Math.floorDiv(startMs, bucketMs);
  }

  /** Rows waiting. */
  long size() {
    return size;
  }

  /** The bucket of an instant. */
  long bucketOf(long ms) {
    return Math.floorDiv(ms, bucketMs);
  }

  /** Adds a row arriving at {@code receivedMs}. */
  void add(Table table, Row row, long receivedMs) {
    long bucket = bucketOf(receivedMs);
    if (bucket < next) {
      bucket = next; // arrives in a bucket already released: goes out with the next release
    }
    if (bucket - next >= buckets) {
      throw new IllegalStateException(
          "Arrival delay beyond " + SECONDS + " s: " + (bucket - next) * bucketMs + " ms");
    }
    int slot = (int) Math.floorMod(bucket, (long) buckets);
    List<Pending> list = ring.get(slot);
    if (list == null) {
      list = new ArrayList<>();
      ring.set(slot, list);
    }
    list.add(new Pending(table, row, receivedMs));
    size++;
  }

  /** Releases every row received up to and including {@code bucket}. */
  void releaseThrough(long bucket, RowConsumer consumer) {
    while (next <= bucket) {
      List<Pending> list = ring.get((int) Math.floorMod(next, (long) buckets));
      if (list != null && !list.isEmpty()) {
        for (Pending p : list) {
          consumer.accept(p.table(), p.row(), p.receivedMs());
        }
        size -= list.size();
        list.clear();
      }
      next++;
    }
  }

  /** Releases everything left. */
  void drain(RowConsumer consumer) {
    long last = next + buckets;
    while (size > 0 && next < last) {
      releaseThrough(next, consumer);
    }
  }
}
