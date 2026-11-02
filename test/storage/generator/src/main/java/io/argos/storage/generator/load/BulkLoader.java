/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.RowBinaryWriter;
import io.argos.storage.generator.rows.Table;
import io.argos.storage.generator.sim.RowConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bulk mode (13 §15): rows in arrival order (so date order, with late rows in later blocks) are cut
 * into large blocks of {@code block-rows} rows per table and inserted by one thread per table (at
 * most 5 connections, 13 §7.4: 8), each block with its own token. Failures are retried 3 times with
 * the same token, then the run stops.
 */
public final class BulkLoader implements RowConsumer, AutoCloseable {

  private static final Block POISON =
      new Block(Table.XLOG, -1, "poison:1:0-0", 0, new byte[0], new long[0], 0);

  private final GeneratorConfig config;
  private final String runId;
  private final RowBinaryWriter[] buffers = new RowBinaryWriter[Table.ALL.size()];
  private final int[] bufferedRows = new int[Table.ALL.size()];
  private final long[] sequence = new long[Table.ALL.size()];
  private final long[] firstPosition = new long[Table.ALL.size()];
  private final long[] lastPosition = new long[Table.ALL.size()];

  /** Position of the next row of the run over all tables (the bulk stand-in for WAL positions). */
  private long nextPosition;

  private final List<BlockingQueue<Block>> queues = new ArrayList<>();
  private final List<Thread> workers = new ArrayList<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final LoaderStats stats;
  private boolean closed;

  public BulkLoader(GeneratorConfig config, String runId, Inserter inserter, LoaderStats stats) {
    this.config = config;
    this.runId = runId;
    this.stats = stats;
    for (Table table : Table.ALL) {
      BlockingQueue<Block> queue = new ArrayBlockingQueue<>(1);
      queues.add(queue);
      BlockSender sender =
          new BlockSender(
              inserter,
              stats,
              3,
              4000,
              config.seed,
              config.duplicateRate,
              config.forceDuplicate,
              System::currentTimeMillis);
      Thread worker = new Thread(() -> work(queue, sender), "argos-bulk-" + table.name());
      worker.setDaemon(true);
      workers.add(worker);
    }
    for (Thread worker : workers) {
      worker.start();
    }
  }

  private void work(BlockingQueue<Block> queue, BlockSender sender) {
    try {
      while (true) {
        Block block = queue.take();
        if (block == POISON) {
          return;
        }
        if (failure.get() == null) {
          sender.send(block);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      failure.compareAndSet(null, e);
      // Keep draining so that the producer never blocks on a dead worker.
      try {
        while (queue.take() != POISON) {
          // discard
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void accept(Table table, Row row, long receivedMs) {
    int t = table.index();
    RowBinaryWriter buffer = buffers[t];
    if (buffer == null) {
      buffer =
          new RowBinaryWriter(
              (int)
                  Math.min(16L << 20, (long) config.blockRows * table.estimatedRowBytes() + 4096));
      table.writeHeader(buffer);
      buffers[t] = buffer;
    }
    row.write(buffer);
    long position = nextPosition++;
    if (bufferedRows[t] == 0) {
      firstPosition[t] = position;
    }
    lastPosition[t] = position;
    if (++bufferedRows[t] >= config.blockRows) {
      seal(table);
    }
  }

  private void seal(Table table) {
    int t = table.index();
    if (bufferedRows[t] == 0) {
      return;
    }
    checkFailure();
    long seq = sequence[t]++;
    Block block =
        new Block(
            table,
            seq,
            DedupToken.of(DedupToken.bulkInstance(runId), 1, firstPosition[t], lastPosition[t]),
            bufferedRows[t],
            buffers[t].toByteArray(),
            new long[0],
            0);
    buffers[t].reset();
    table.writeHeader(buffers[t]);
    bufferedRows[t] = 0;
    try {
      queues.get(t).put(block);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while queueing " + block, e);
    }
  }

  private void checkFailure() {
    Throwable f = failure.get();
    if (f != null) {
      throw new IllegalStateException("Insert failed: " + f.getMessage(), f);
    }
  }

  /** Statistics of the inserts. */
  public LoaderStats stats() {
    return stats;
  }

  /** Seals the partial blocks, waits for every insert, rethrows a failure. */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      try {
        for (Table table : Table.ALL) {
          seal(table);
        }
      } finally {
        for (BlockingQueue<Block> queue : queues) {
          queue.put(POISON);
        }
        for (Thread worker : workers) {
          worker.join();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the inserts", e);
    }
    checkFailure();
  }
}
