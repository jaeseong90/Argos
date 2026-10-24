/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.model.Hash;
import io.argos.storage.generator.rows.Table;
import java.util.function.LongSupplier;

/**
 * Sends a block with the writer's failure rule (13 §7.4, 12 §3.8): on failure retry with the same
 * token after 1, 2, 4 ... s (capped), up to {@code maxRetries}; afterwards the block is re-sent
 * once more with the same token when the duplicate policy picks it (D-13 duplicate re-send).
 */
final class BlockSender {

  private static final long DUPLICATE_SALT = 0x647570L;

  private final Inserter inserter;
  private final LoaderStats stats;
  private final int maxRetries;
  private final long maxBackoffMs;
  private final long seed;
  private final double duplicateRate;
  private final boolean forceDuplicate;
  private final LongSupplier clock;

  BlockSender(
      Inserter inserter,
      LoaderStats stats,
      int maxRetries,
      long maxBackoffMs,
      long seed,
      double duplicateRate,
      boolean forceDuplicate,
      LongSupplier clock) {
    this.inserter = inserter;
    this.stats = stats;
    this.maxRetries = maxRetries;
    this.maxBackoffMs = maxBackoffMs;
    this.seed = seed;
    this.duplicateRate = duplicateRate;
    this.forceDuplicate = forceDuplicate;
    this.clock = clock;
  }

  /** Whether block {@code sequence} of {@code table} is re-sent once (deterministic). */
  static boolean duplicate(long seed, double rate, boolean force, Table table, long sequence) {
    if (force && sequence == 0) {
      return true;
    }
    return rate > 0 && Hash.unit(Hash.of(seed, DUPLICATE_SALT, table.index(), sequence)) < rate;
  }

  /** First send (with retries), then the duplicate re-send if picked; returns the ack time. */
  long send(Block block) throws InterruptedException {
    long start = clock.getAsLong();
    long written = insertWithRetry(block);
    long ack = clock.getAsLong();
    stats.inserted(block, start, ack, written);
    if (duplicate(seed, duplicateRate, forceDuplicate, block.table(), block.sequence())) {
      stats.resent(block, insertWithRetry(block), false);
    }
    return ack;
  }

  /** A recovery re-insert (same token). */
  void resendForRecovery(Block block) throws InterruptedException {
    stats.resent(block, insertWithRetry(block), true);
  }

  /** Inserts with retries; returns written_rows. */
  long insertWithRetry(Block block) throws InterruptedException {
    long backoff = Math.min(1000, maxBackoffMs);
    for (int attempt = 0; ; attempt++) {
      try {
        return inserter.insert(block);
      } catch (RuntimeException e) {
        if (attempt >= maxRetries) {
          throw new IllegalStateException(
              "Insert failed after " + (attempt + 1) + " attempts: " + block, e);
        }
        stats.retried(block.table());
        Thread.sleep(backoff);
        backoff = Math.min(maxBackoffMs, backoff * 2);
      }
    }
  }
}
