/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.google.protobuf.ByteString;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.api.BlockIntent;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.storage.api.ClickHouseWriter;
import io.argos.server.storage.api.EncodedRows;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.query.ClickHouseErrors;
import io.argos.server.wal.WalCoverage;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriteException;
import io.argos.server.wal.api.WalWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The ClickHouse batch writer (12 §3.8, 13 §5, §7.4, ADR-0007 C-58, C-60, C-61): one {@code
 * argos-ch-writer-<table>} thread and one bounded queue per raw table.
 *
 * <p>Rows arrive in WAL order (the ingest dispatcher delivers every data record in order, with an
 * empty marker when a record has no rows for a table), so every block holds all rows of its table
 * from the data records between its first and last position: recovery can rebuild an uncommitted
 * block from its intent's range and send it again with the same token (12 §3.4).
 *
 * <p>Per block, every {@code argos.writer.flush-interval-ms} (2 s, counted from the oldest queued
 * entry) or {@code argos.writer.flush-rows} (50,000; a record's rows are never split):
 *
 * <ol>
 *   <li>{@link BlockToken#of} over the first and last WAL position of its rows (C-61);
 *   <li>{@code BLOCK_INTENT} appended to the WAL and fsynced;
 *   <li>the insert ({@link BlockInserter}, {@code argos_writer} only), retried with backoff 1 s →
 *       30 s and the same token until it succeeds; duplicates are never judged by {@code
 *       written_rows};
 *   <li>{@code BLOCK_COMMIT} appended.
 * </ol>
 *
 * <p>A full queue makes {@link #enqueue} return {@code false} at once; the dispatcher then waits
 * ({@link #offer} with a timeout) and, past its own budget, leaves the records in the WAL and reads
 * them back later (WAL = spool). Metrics: {@code argos_writer_rows_total{table}}, {@code
 * argos_writer_inserts_total{table,result}}, {@code argos_writer_queue_rows{table}}, {@code
 * argos_ingest_lag_seconds} (receive → insert ack, p50/p99 and histogram, N-15).
 *
 * <p>WO-28 adds two things:
 *
 * <ul>
 *   <li>Dead-letter (ADR-0010 C-158 (3)): when ClickHouse refuses the same block {@value
 *       #DEAD_LETTER_ATTEMPTS} times with a code that refuses its content ({@link
 *       ClickHouseErrors#deterministicInsertCode}), the block goes to the {@link DeadLetterStore}
 *       and its intent is committed like an insert, so the WAL coverage moves on and the other
 *       blocks and tables keep loading. Any other failure is retried without end, as before; a
 *       writer without a store (tests) retries everything.
 *   <li>Insert pause (F-S23, 13 §8): {@link #pauseInserts} stops taking blocks while the ClickHouse
 *       disk is above the stop ratio; the queues fill, the dispatcher waits and the records stay in
 *       the WAL (its bound and its drop accounting apply unchanged) until {@link #resumeInserts}.
 * </ul>
 */
@Component
public final class ClickHouseBatchWriter implements ClickHouseWriter, WalCoverage, DisposableBean {

  /** Shutdown budget of the last flush (12 §3.9). */
  static final long CLOSE_FLUSH_MS = 5_000;

  /** Backoff bounds of a failed insert (12 §3.8). */
  static final long BACKOFF_MIN_MS = 1_000;

  static final long BACKOFF_MAX_MS = 30_000;

  /** Deterministic refusals of one block before it goes to the dead-letter area (C-158 (3)). */
  public static final int DEAD_LETTER_ATTEMPTS = 3;

  private static final Logger LOG = LoggerFactory.getLogger(ClickHouseBatchWriter.class);

  private final WalWriter wal;
  private final int flushIntervalMs;
  private final int flushRows;
  private final int queueRows;
  private final ArgosProperties.ClickHouse clickhouse;
  private final MeterRegistry registry;
  private final Clock clock;
  private final Map<Table, TableWriter> writers = new EnumMap<>(Table.class);
  private final Timer lag;
  private volatile BlockInserter inserter;
  private volatile long backoffMinMs = BACKOFF_MIN_MS;
  private volatile DeadLetterStore deadLetter;
  private volatile boolean insertsPaused;
  private AutoCloseable ownedInserter;

  /** The bean of the server: {@code argos.writer.*}, {@code argos.clickhouse.*}. */
  @Autowired
  public ClickHouseBatchWriter(
      ArgosProperties properties,
      WalWriter wal,
      MeterRegistry registry,
      DeadLetterStore deadLetter) {
    this(
        wal,
        properties.writer().flushIntervalMs(),
        properties.writer().flushRows(),
        properties.writer().queueRows(),
        properties.clickhouse(),
        registry,
        Clock.systemUTC());
    this.deadLetter = deadLetter;
  }

  /**
   * A writer with explicit settings (tests).
   *
   * @param clickhouse connection settings of {@link #start()}; {@code null} when {@link
   *     #start(BlockInserter)} is used
   */
  public ClickHouseBatchWriter(
      WalWriter wal,
      int flushIntervalMs,
      int flushRows,
      int queueRows,
      ArgosProperties.ClickHouse clickhouse,
      MeterRegistry registry,
      Clock clock) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.flushIntervalMs = flushIntervalMs;
    this.flushRows = flushRows;
    this.queueRows = queueRows;
    this.clickhouse = clickhouse;
    this.registry = registry;
    this.clock = clock;
    this.lag =
        Timer.builder("argos.ingest.lag")
            .description("Server receive time to ClickHouse insert acknowledgement (N-15)")
            .publishPercentiles(0.5, 0.99)
            .publishPercentileHistogram()
            .register(registry);
    for (Table table : Table.values()) {
      writers.put(table, new TableWriter(table));
    }
  }

  /** Opens the {@code argos_writer} client and starts the table threads. */
  public synchronized void start() {
    ClickHouseBlockInserter opened = ClickHouseBlockInserter.open(clickhouse);
    ownedInserter = opened;
    start(opened);
  }

  /** Starts the table threads with {@code blockInserter} (tests, or {@link #start()}). */
  public synchronized void start(BlockInserter blockInserter) {
    if (inserter != null) {
      throw new IllegalStateException("The writer is already started");
    }
    inserter = Objects.requireNonNull(blockInserter, "blockInserter");
    for (TableWriter writer : writers.values()) {
      writer.start();
    }
  }

  /** Shortens the retry backoff (tests of outages that must not wait 30 s between attempts). */
  public void setBackoffMinMs(long ms) {
    backoffMinMs = Math.max(1, ms);
  }

  /**
   * Sets the dead-letter area (a writer built with the test constructor has none and retries every
   * failure without end).
   */
  public void setDeadLetter(DeadLetterStore store) {
    deadLetter = store;
  }

  /**
   * Stops taking new blocks (F-S23: the ClickHouse disk is above the stop ratio); a block being
   * inserted finishes. Records stay in the WAL until {@link #resumeInserts}.
   */
  public void pauseInserts() {
    if (!insertsPaused) {
      insertsPaused = true;
      LOG.warn("ClickHouse inserts paused: data is kept in the WAL only until they resume");
    }
  }

  /** Takes blocks again after {@link #pauseInserts}. */
  public void resumeInserts() {
    if (insertsPaused) {
      insertsPaused = false;
      LOG.info("ClickHouse inserts resumed: the WAL is loaded again");
      for (TableWriter writer : writers.values()) {
        writer.requestFlush();
      }
    }
  }

  /** Whether inserts are paused ({@link #pauseInserts}). */
  public boolean insertsPaused() {
    return insertsPaused;
  }

  /** Whether the threads run. */
  public boolean isStarted() {
    return inserter != null;
  }

  // --- ClickHouseWriter ---------------------------------------------------------------------

  @Override
  public boolean enqueue(Table table, WalPosition position, EncodedRows rows) {
    return offer(table, position, rows, clock.millis(), 0);
  }

  @Override
  public int queuedRows(Table table) {
    return writers.get(table).queuedRows;
  }

  @Override
  public int queueCapacityRows() {
    return queueRows;
  }

  // --- dispatcher side ----------------------------------------------------------------------

  /**
   * Queues the rows of {@code table} from the data record at {@code position}, or an empty marker
   * when {@code rows} is {@code null} (the record has no rows for the table), waiting up to {@code
   * timeoutMs} for space.
   *
   * @param receivedMs server receive time of the record (lag metric)
   * @return {@code false} when there was no space in time or the writer stops
   */
  public boolean offer(
      Table table, WalPosition position, EncodedRows rows, long receivedMs, long timeoutMs) {
    return writers.get(table).offer(new Entry(position, rows, receivedMs), timeoutMs);
  }

  /**
   * Sets what recovery found inserted for {@code table}: every row of records up to {@code packed}
   * (the last position of its last intent, now committed).
   */
  public void initCoverage(Table table, long packed) {
    TableWriter writer = writers.get(table);
    synchronized (writer) {
      if (writer.durableThrough < 0 || Long.compareUnsigned(packed, writer.durableThrough) > 0) {
        writer.durableThrough = packed;
      }
    }
  }

  /**
   * Inserts the block of an intent found without a commit, with the intent's own token, and appends
   * the commit (12 §3.4: before anything else, no time limit).
   *
   * @param intent the intent from the WAL
   * @param rows the rows rebuilt from the intent's range, in WAL order; empty when none could be
   *     rebuilt
   * @throws InterruptedException when the start is interrupted
   */
  public void reinsert(BlockIntent intent, List<EncodedRows> rows) throws InterruptedException {
    TableWriter writer = writers.get(intent.table());
    int rowCount = rows.stream().mapToInt(EncodedRows::rowCount).sum();
    if (rowCount == 0) {
      LOG.warn(
          "WAL recovery: block {} of {} has no rows to send again (its records are gone);"
              + " committed without an insert",
          intent.token(),
          intent.table().tableName());
    } else {
      ByteString data = writer.header;
      for (EncodedRows r : rows) {
        data = data.concat(r.rowBinary());
      }
      writer.insertWithRetry(intent.token(), data, rowCount, true);
    }
    writer.commit(intent.token(), true);
  }

  /**
   * Makes every table flush what is queued now instead of at the interval; entries offered later
   * wait for the interval or the row count again.
   */
  public void flushNow() {
    for (TableWriter writer : writers.values()) {
      writer.requestFlush();
    }
  }

  /**
   * Waits until every queue is empty and no block is in progress.
   *
   * @return {@code true} when idle within {@code timeoutMs}
   */
  public boolean awaitIdle(long timeoutMs) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (true) {
      boolean idle = true;
      for (TableWriter writer : writers.values()) {
        if (!writer.isIdle()) {
          idle = false;
          writer.requestFlush();
        }
      }
      if (idle) {
        return true;
      }
      if (System.nanoTime() > deadline) {
        return false;
      }
      Thread.sleep(20);
    }
  }

  /** The packed position up to which {@code table} has committed every row (-1: none). */
  public long durableThrough(Table table) {
    return writers.get(table).durableThrough;
  }

  // --- WalCoverage --------------------------------------------------------------------------

  @Override
  public long durableThroughPacked(WalRecordType dataType) {
    long min = Long.MAX_VALUE;
    boolean any = false;
    for (Table table : tablesOf(dataType)) {
      long through = writers.get(table).durableThrough;
      if (through < 0) {
        return -1;
      }
      if (!any || Long.compareUnsigned(through, min) < 0) {
        min = through;
      }
      any = true;
    }
    return any ? min : -1;
  }

  @Override
  public List<WalPosition> pendingIntents() {
    List<WalPosition> pending = new ArrayList<>();
    for (TableWriter writer : writers.values()) {
      WalPosition p = writer.pendingIntent;
      if (p != null) {
        pending.add(p);
      }
    }
    return pending;
  }

  /** The raw tables fed by records of {@code type} (12 §3.5, §3.6). */
  public static List<Table> tablesOf(WalRecordType type) {
    return switch (type) {
      case TX -> List.of(Table.XLOG);
      case PROFILE -> List.of(Table.PROFILE_STEP);
      case METRIC -> List.of(Table.METRIC);
      case STAT -> List.of(Table.SQL_STAT_RAW, Table.EXT_STAT_RAW, Table.METRIC);
      case BLOCK_INTENT, BLOCK_COMMIT -> List.of();
    };
  }

  // --- shutdown -----------------------------------------------------------------------------

  /**
   * Flushes what is queued within {@value #CLOSE_FLUSH_MS} ms, then stops the threads (12 §3.9); an
   * insert still being retried is abandoned and its intent stays for the recovery.
   */
  public void close() {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_FLUSH_MS);
    for (TableWriter writer : writers.values()) {
      writer.stopping = true;
      writer.requestFlush();
    }
    for (TableWriter writer : writers.values()) {
      writer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
    }
    int left = 0;
    for (TableWriter writer : writers.values()) {
      left += writer.queuedRows;
      writer.stopNow();
    }
    if (inserter != null) {
      // 12 §3.9: rows not inserted within the budget stay in the WAL and load at the next start.
      LOG.info(
          "ClickHouse writer stopped after its last flush ({} rows left to the WAL recovery)",
          left);
    }
    AutoCloseable owned = ownedInserter;
    if (owned != null) {
      try {
        owned.close();
      } catch (Exception e) {
        LOG.debug("Closing the ClickHouse writer client failed: {}", e.toString());
      }
    }
  }

  /**
   * Stops the threads at once, without the last flush: what a crash leaves behind (an insert in
   * progress is abandoned, its intent stays without a commit). Crash and recovery tests only.
   */
  public void abort() {
    for (TableWriter writer : writers.values()) {
      writer.stopping = true;
      writer.stopNow();
    }
    AutoCloseable owned = ownedInserter;
    if (owned != null) {
      try {
        owned.close();
      } catch (Exception e) {
        LOG.debug("Closing the ClickHouse writer client failed: {}", e.toString());
      }
    }
  }

  @Override
  public void destroy() {
    close();
  }

  // --- internals ----------------------------------------------------------------------------

  /** How {@code insertWithRetry} ended. */
  enum Sent {
    /** ClickHouse confirmed the insert. */
    INSERTED,
    /**
     * ClickHouse refused the content; the block went to the dead-letter area (or was discarded).
     */
    DEAD_LETTERED,
    /** The writer stopped first. */
    STOPPED
  }

  /** One queued item: the rows of one data record for one table, or a marker without rows. */
  private record Entry(WalPosition position, EncodedRows rows, long receivedMs) {
    int rowCount() {
      return rows == null ? 0 : rows.rowCount();
    }
  }

  private final class TableWriter implements Runnable {

    final Table table;
    final ByteString header;
    final ReentrantLock lock = new ReentrantLock();
    final Condition changed = lock.newCondition();
    final ArrayDeque<Entry> queue = new ArrayDeque<>();
    final ArrayDeque<Long> enqueuedAt = new ArrayDeque<>();
    final Counter rowsTotal;
    final Counter insertsOk;
    final Counter insertsFailed;
    int queued; // guarded by lock
    volatile int queuedRows; // snapshot of queued for gauges and readers
    volatile long durableThrough = -1;
    volatile WalPosition pendingIntent;
    volatile boolean stopping;
    volatile boolean stopNow;
    volatile boolean processing;
    boolean flushRequested; // guarded by lock
    Thread thread;

    TableWriter(Table table) {
      this.table = table;
      this.header = RowBinaryBuffer.header(table);
      this.rowsTotal =
          Counter.builder("argos.writer.rows")
              .tag("table", table.tableName())
              .description("Rows inserted into ClickHouse")
              .register(registry);
      this.insertsOk =
          Counter.builder("argos.writer.inserts")
              .tag("table", table.tableName())
              .tag("result", "ok")
              .description("ClickHouse insert attempts")
              .register(registry);
      this.insertsFailed =
          Counter.builder("argos.writer.inserts")
              .tag("table", table.tableName())
              .tag("result", "error")
              .description("ClickHouse insert attempts")
              .register(registry);
      Gauge.builder("argos.writer.queue.rows", this, w -> w.queuedRows)
          .tag("table", table.tableName())
          .description("Rows waiting in the writer queue")
          .register(registry);
    }

    void start() {
      Thread t = new Thread(this, "argos-ch-writer-" + table.tableName());
      t.setDaemon(false);
      thread = t;
      t.start();
    }

    int maxEntries() {
      return Math.max(1024, queueRows);
    }

    boolean offer(Entry entry, long timeoutMs) {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
      lock.lock();
      try {
        while (!queue.isEmpty()
            && (queued + entry.rowCount() > queueRows || queue.size() >= maxEntries())) {
          if (stopping) {
            return false;
          }
          long left = deadline - System.nanoTime();
          if (left <= 0) {
            return false;
          }
          changed.awaitNanos(left);
        }
        if (stopping) {
          return false;
        }
        queue.add(entry);
        enqueuedAt.add(System.nanoTime());
        queued += entry.rowCount();
        queuedRows = queued;
        if (queued >= flushRows) {
          changed.signalAll();
        }
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } finally {
        lock.unlock();
      }
    }

    void requestFlush() {
      lock.lock();
      try {
        // A request flushes what is queued now; on an empty queue it is not remembered, or the
        // next entry (minutes later) would be flushed alone instead of at the interval.
        if (!queue.isEmpty()) {
          flushRequested = true;
        }
        changed.signalAll();
      } finally {
        lock.unlock();
      }
    }

    boolean isIdle() {
      lock.lock();
      try {
        return queue.isEmpty() && !processing;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void run() {
      try {
        while (true) {
          List<Entry> batch = take();
          if (batch == null) {
            return;
          }
          try {
            process(batch);
          } finally {
            processing = false;
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException | Error e) {
        LOG.error("ClickHouse writer of {} failed: {}", table.tableName(), e.toString(), e);
      }
    }

    /** The next block to process, or {@code null} when the writer stops. */
    private List<Entry> take() throws InterruptedException {
      lock.lock();
      try {
        while (true) {
          if (stopNow || (stopping && queue.isEmpty())) {
            return null;
          }
          if (insertsPaused) {
            if (stopping) {
              return null; // 12 §3.9: what is queued stays in the WAL for the recovery
            }
            changed.await(1, TimeUnit.SECONDS);
            continue;
          }
          if (!queue.isEmpty()) {
            long ageMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueuedAt.peekFirst());
            if (queued >= flushRows || flushRequested || stopping || ageMs >= flushIntervalMs) {
              break;
            }
            changed.await(Math.max(1, flushIntervalMs - ageMs), TimeUnit.MILLISECONDS);
          } else {
            changed.await(1, TimeUnit.SECONDS);
          }
        }
        List<Entry> batch = new ArrayList<>();
        int rows = 0;
        while (!queue.isEmpty()) {
          Entry head = queue.peekFirst();
          if (rows > 0 && rows + head.rowCount() > flushRows) {
            break;
          }
          queue.pollFirst();
          enqueuedAt.pollFirst();
          batch.add(head);
          rows += head.rowCount();
        }
        queued -= rows;
        queuedRows = queued;
        if (queue.isEmpty()) {
          flushRequested = false;
        }
        processing = true;
        changed.signalAll();
        return batch;
      } finally {
        lock.unlock();
      }
    }

    private void process(List<Entry> batch) throws InterruptedException {
      List<Entry> withRows = new ArrayList<>(batch.size());
      int rowCount = 0;
      for (Entry e : batch) {
        if (e.rows() != null) {
          withRows.add(e);
          rowCount += e.rowCount();
        }
      }
      if (!withRows.isEmpty()) {
        WalPosition first = withRows.get(0).position();
        WalPosition last = withRows.get(withRows.size() - 1).position();
        BlockToken token = BlockToken.of(wal.identity(), first, last);
        BlockIntent intent = new BlockIntent(table, first, last, token);
        WalPosition intentPosition = appendWithRetry(intent);
        if (intentPosition == null) {
          return; // stopping: nothing inserted, the records are read again after the restart
        }
        pendingIntent = intentPosition;
        ByteString data = header;
        for (Entry e : withRows) {
          data = data.concat(e.rows().rowBinary());
        }
        Sent sent = insertWithRetry(token, data, rowCount, false);
        if (sent == Sent.STOPPED) {
          return; // stopping: the intent stays without a commit; recovery sends the block again
        }
        if (!commit(token, false)) {
          return;
        }
        if (sent == Sent.INSERTED) {
          long now = clock.millis();
          for (Entry e : withRows) {
            lag.record(Math.max(0, now - e.receivedMs()), TimeUnit.MILLISECONDS);
          }
        }
      }
      synchronized (this) {
        long last = batch.get(batch.size() - 1).position().packed();
        if (durableThrough < 0 || Long.compareUnsigned(last, durableThrough) > 0) {
          durableThrough = last;
        }
      }
    }

    /** Appends the intent; {@code null} when the writer stops first. */
    private WalPosition appendWithRetry(BlockIntent intent) throws InterruptedException {
      long backoff = backoffMinMs;
      int attempt = 0;
      while (true) {
        try {
          return wal.appendBlockIntent(intent.encode());
        } catch (WalWriteException e) {
          attempt++;
          if (attempt == 1 || attempt % 20 == 0) {
            LOG.warn(
                "Block intent of {} not written to the WAL (attempt {}): {}",
                table.tableName(),
                attempt,
                e.getMessage());
          }
          if (stopNow || (stopping && attempt > 3)) {
            return null;
          }
          backoff = sleepBackoff(backoff);
        }
      }
    }

    /**
     * Inserts until ClickHouse confirms, or until it refused the block's content {@value
     * #DEAD_LETTER_ATTEMPTS} times and the block went to the dead-letter area; {@link Sent#STOPPED}
     * when the writer stops first ({@code untilDone} ignores stopping: recovery has no time limit).
     */
    Sent insertWithRetry(BlockToken token, ByteString data, int rowCount, boolean untilDone)
        throws InterruptedException {
      long backoff = backoffMinMs;
      int attempt = 0;
      int refusals = 0;
      long firstFailure = 0;
      while (true) {
        try {
          inserter.insert(table, token, data);
          insertsOk.increment();
          rowsTotal.increment(rowCount);
          if (attempt > 0) {
            LOG.info(
                "Insert into {} succeeded after {} failed attempts ({} s)",
                table.tableName(),
                attempt,
                TimeUnit.MILLISECONDS.toSeconds(clock.millis() - firstFailure));
          }
          return Sent.INSERTED;
        } catch (InterruptedException e) {
          if (untilDone) {
            throw e;
          }
          Thread.currentThread().interrupt();
          return Sent.STOPPED;
        } catch (Exception e) {
          insertsFailed.increment();
          attempt++;
          if (attempt == 1) {
            firstFailure = clock.millis();
          }
          int code = ClickHouseErrors.deterministicInsertCode(e);
          DeadLetterStore store = deadLetter;
          if (code > 0 && store != null && ++refusals >= DEAD_LETTER_ATTEMPTS) {
            store.store(table, token, data, rowCount, code);
            return Sent.DEAD_LETTERED;
          }
          if (attempt == 1 || attempt % 10 == 0) {
            LOG.warn(
                "Insert into {} failed (attempt {}, token {}), retried with the same token: {}",
                table.tableName(),
                attempt,
                token,
                oneLine(e));
          }
          if (!untilDone && stopNow) {
            return Sent.STOPPED;
          }
          backoff = sleepBackoff(backoff);
        }
      }
    }

    /** Appends the commit; {@code false} when it could not be written before stopping. */
    boolean commit(BlockToken token, boolean untilDone) throws InterruptedException {
      long backoff = backoffMinMs;
      int attempt = 0;
      while (true) {
        try {
          wal.appendBlockCommit(token.commitPayload());
          pendingIntent = null;
          return true;
        } catch (WalWriteException e) {
          attempt++;
          if (attempt == 1 || attempt % 20 == 0) {
            LOG.warn(
                "Block commit of {} not written to the WAL (attempt {}): {}; recovery sends the"
                    + " block again with the same token",
                table.tableName(),
                attempt,
                e.getMessage());
          }
          if (!untilDone && (stopNow || (stopping && attempt > 3))) {
            return false;
          }
          if (untilDone && attempt > 3) {
            throw new IllegalStateException("The WAL cannot record block commits", e);
          }
          backoff = sleepBackoff(backoff);
        }
      }
    }

    private long sleepBackoff(long backoff) throws InterruptedException {
      long slept = 0;
      while (slept < backoff && !stopNow) {
        long step = Math.min(100, backoff - slept);
        Thread.sleep(step);
        slept += step;
      }
      return Math.min(backoff * 2, BACKOFF_MAX_MS);
    }

    void join(long timeoutMs) {
      Thread t = thread;
      if (t == null) {
        return;
      }
      try {
        t.join(timeoutMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    void stopNow() {
      stopNow = true;
      lock.lock();
      try {
        changed.signalAll();
      } finally {
        lock.unlock();
      }
      Thread t = thread;
      if (t != null && t.isAlive()) {
        t.interrupt();
        try {
          t.join(2_000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static String oneLine(Throwable e) {
    Throwable cause =
        e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
            ? e.getCause()
            : e;
    String message = cause.getMessage();
    String text =
        cause.getClass().getSimpleName()
            + (message == null ? "" : ": " + message.lines().findFirst().orElse(""));
    return text.length() <= 300 ? text : text.substring(0, 300);
  }
}
