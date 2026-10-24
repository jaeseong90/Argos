/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.load.WalSimulator.Intent;
import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.RowBinaryWriter;
import io.argos.storage.generator.rows.Table;
import io.argos.storage.generator.sim.RowConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Replay mode (13 §15): the path of the server's batch writer (12 §3.4, §3.8). Every received row
 * becomes a WAL record; one writer thread per table flushes every {@code flush-interval-ms} (2 s)
 * or {@code flush-rows} (50,000) rows: BLOCK_INTENT, insert with the token derived from the intent
 * position, BLOCK_COMMIT after the ack. Re-sent blocks (duplicate policy) and simulated writer
 * restarts (crash before the insert or between the insert and the commit, then recovery of every
 * intent without a commit with the same token) exercise the D-13 contract against the server. The
 * ingest lag (receive to ack, N-06) is recorded per row.
 */
public final class ReplayLoader implements RowConsumer, AutoCloseable {

  private static final class Queue {
    final Table table;
    final RowBinaryWriter buffer;
    long[] received = new long[1024];
    int rows;
    long firstPosition;
    long lastPosition;
    long firstWallMs;
    long minReceived;
    long sequence;

    Queue(Table table) {
      this.table = table;
      this.buffer = new RowBinaryWriter(1 << 20);
      table.writeHeader(buffer);
    }
  }

  private final GeneratorConfig config;
  private final WalSimulator wal;
  private final LoaderStats stats;
  private final LongSupplier wallClock;
  private final Queue[] queues = new Queue[Table.ALL.size()];
  private final List<Thread> writers = new ArrayList<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final long[] crashAt;
  private int nextCrash;
  private long crashesBeforeInsert;
  private long crashesAfterInsert;
  private volatile boolean closing;

  /**
   * @param config flush interval and rows, duplicate policy, writer restarts
   * @param wal the WAL (block ids)
   * @param inserter destination
   * @param stats insert statistics (lag included)
   * @param wallClock wall clock in ms
   * @param crashAt receive times at which the xlog writer simulates a restart (ascending)
   */
  public ReplayLoader(
      GeneratorConfig config,
      WalSimulator wal,
      Inserter inserter,
      LoaderStats stats,
      LongSupplier wallClock,
      long[] crashAt) {
    this.config = config;
    this.wal = wal;
    this.stats = stats;
    this.wallClock = wallClock;
    this.crashAt = crashAt.clone();
    for (Table table : Table.ALL) {
      Queue queue = new Queue(table);
      queues[table.index()] = queue;
      BlockSender sender =
          new BlockSender(
              inserter,
              stats,
              Integer.MAX_VALUE,
              30_000,
              config.seed,
              config.duplicateRate,
              config.forceDuplicate,
              wallClock);
      Thread writer = new Thread(() -> write(queue, sender), "argos-writer-" + table.name());
      writer.setDaemon(true);
      writers.add(writer);
    }
    for (Thread writer : writers) {
      writer.start();
    }
  }

  @Override
  public void accept(Table table, Row row, long receivedMs) {
    Throwable f = failure.get();
    if (f != null) {
      throw new IllegalStateException("Writer failed: " + f.getMessage(), f);
    }
    Queue q = queues[table.index()];
    synchronized (q) {
      int before = q.buffer.length();
      row.write(q.buffer);
      long position = wal.append(q.buffer.length() - before);
      if (q.rows == 0) {
        q.firstPosition = position;
        q.firstWallMs = wallClock.getAsLong();
        q.minReceived = receivedMs;
      }
      q.lastPosition = position;
      if (q.rows == q.received.length) {
        q.received = Arrays.copyOf(q.received, q.received.length * 2);
      }
      q.received[q.rows++] = receivedMs;
      q.minReceived = Math.min(q.minReceived, receivedMs);
      stats.queued(table, q.rows);
    }
  }

  private void write(Queue q, BlockSender sender) {
    try {
      while (true) {
        Block block = null;
        Intent intent = null;
        synchronized (q) {
          boolean stop = closing && q.rows == 0;
          if (stop) {
            return;
          }
          boolean due =
              q.rows > 0
                  && (closing
                      || q.rows >= config.flushRows
                      || wallClock.getAsLong() - q.firstWallMs >= config.flushIntervalMs);
          if (due) {
            intent = wal.intent(q.table, q.firstPosition, q.lastPosition, q.rows);
            block =
                new Block(
                    q.table,
                    q.sequence++,
                    intent.blockId(),
                    q.rows,
                    q.buffer.toByteArray(),
                    Arrays.copyOf(q.received, q.rows),
                    q.minReceived);
            q.buffer.reset();
            q.table.writeHeader(q.buffer);
            q.rows = 0;
          }
        }
        if (block == null) {
          Thread.sleep(10);
        } else {
          insert(block, intent, sender);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      failure.compareAndSet(null, e);
    }
  }

  private void insert(Block block, Intent intent, BlockSender sender) throws InterruptedException {
    int crash = block.table() == Table.XLOG ? crashDue(block.minReceivedMs()) : 0;
    if (crash != 0) {
      if (crash == 1) {
        // Crash after BLOCK_INTENT, before the insert: recovery inserts the block for real.
        recover(sender, block, true);
      } else {
        // Crash after the insert, before BLOCK_COMMIT: recovery re-sends a stored block.
        long ack = sender.send(block);
        stats.lag(block, ack);
        recover(sender, block, false);
      }
      return;
    }
    long ack = sender.send(block);
    wal.commit(intent);
    stats.lag(block, ack);
  }

  /** Writer restart: every intent of this table without a commit is inserted with its token. */
  private void recover(BlockSender sender, Block current, boolean firstSend)
      throws InterruptedException {
    for (Intent open : wal.uncommitted()) {
      if (open.table() != current.table() || !open.blockId().equals(current.blockId())) {
        continue; // other writers own their intents; this simulation crashes one block
      }
      if (firstSend) {
        long ack = sender.send(current);
        stats.lag(current, ack);
      } else {
        sender.resendForRecovery(current);
      }
      wal.commit(open);
    }
  }

  /** 0: no crash, 1: crash before the insert, 2: crash between the insert and the commit. */
  private synchronized int crashDue(long receivedMs) {
    if (nextCrash < crashAt.length && receivedMs >= crashAt[nextCrash]) {
      nextCrash++;
      if ((crashesBeforeInsert + crashesAfterInsert) % 2 == 0) {
        crashesBeforeInsert++;
        return 1;
      }
      crashesAfterInsert++;
      return 2;
    }
    return 0;
  }

  /** Simulated restarts {before insert, after insert}. */
  public synchronized long[] crashes() {
    return new long[] {crashesBeforeInsert, crashesAfterInsert};
  }

  /** Statistics of the inserts. */
  public LoaderStats stats() {
    return stats;
  }

  /** Rows queued and not yet sent. */
  public long queuedRows() {
    long n = 0;
    for (Queue q : queues) {
      synchronized (q) {
        n += q.rows;
      }
    }
    return n;
  }

  /** Flushes every queue, waits for the writers, rethrows a failure. */
  @Override
  public void close() {
    closing = true;
    try {
      for (Thread writer : writers) {
        writer.join();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the writers", e);
    }
    Throwable f = failure.get();
    if (f != null) {
      throw new IllegalStateException("Writer failed: " + f.getMessage(), f);
    }
    if (!wal.uncommitted().isEmpty()) {
      throw new IllegalStateException("Intents without commit: " + wal.uncommitted());
    }
  }
}
