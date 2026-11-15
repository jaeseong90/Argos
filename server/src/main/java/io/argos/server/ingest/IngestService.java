/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.ingest.api.IngestPipeline;
import io.argos.server.ingest.api.RealtimePublisher;
import io.argos.server.session.api.SessionContext;
import io.argos.server.storage.api.BlockIntent;
import io.argos.server.storage.api.EncodedRows;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.wal.DurableMark;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalFormat;
import io.argos.server.wal.WalGc;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.WalRecovery;
import io.argos.server.wal.WalTailReader;
import io.argos.server.wal.api.WalPosition;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The ingest pipeline after the WAL (12 §2 {@code IngestPipeline}, §3.5, §3.6, §3.8, §11).
 *
 * <p>One dispatcher thread ({@code argos-ingest-dispatch}) reads the WAL in order with a {@link
 * WalTailReader}, never beyond the durable end, and hands each data record to the decode pool
 * ({@code argos-ingest-1..4}, {@link RecordDecoder}); decoded rows go to the {@link
 * ClickHouseBatchWriter} in WAL order, with an empty marker for a table a record has no rows for.
 * The same path serves recovery, the live flow and the catch-up after a backlog, so every record is
 * decoded from the bytes that were fsynced before its ack (D-13, C-129 (3)):
 *
 * <ul>
 *   <li>The collector's batch calls ({@link #onTransactions} and the others) return at once: the
 *       record is already durable and the dispatcher reads it from the WAL.
 *   <li>When ClickHouse is slow or down the writer queues fill, the dispatcher waits with at most
 *       {@value #MAX_IN_FLIGHT} decoded records in memory and stops reading: the records stay only
 *       in the WAL (the WAL is the spool, 12 §3.8), and are read again as soon as the queues drain
 *       (the {@code WalReplayer} role).
 *   <li>XLog points of rows with {@code late = 0} go to {@link RealtimePublisher#publishXlog} only
 *       for records read within {@value #LIVE_WINDOW_MS} ms of their receipt and never during
 *       recovery (12 §3.7).
 * </ul>
 *
 * <p>Recovery ({@link #recover}, the {@code WAL_RECOVERY} start phase): every intent without a
 * commit is rebuilt from its range and inserted again with its recorded token, before anything
 * else; then the data records after the last intent of each table are loaded as new blocks; the
 * call returns when every record of the old segments is committed (no time limit, progress logged).
 */
@Component
public class IngestService implements IngestPipeline, DisposableBean {

  /** Decode threads (12 §11). */
  static final int DECODE_THREADS = 4;

  /** Records decoded ahead of their delivery. */
  static final int MAX_IN_FLIGHT = 32;

  /** Records read this soon after their receipt are live (real-time eligible). */
  static final long LIVE_WINDOW_MS = 5_000;

  /** How long one offer to a full writer queue waits before the dispatcher checks for stop. */
  private static final long OFFER_SLICE_MS = 500;

  private static final long PROGRESS_INTERVAL_MS = 10_000;

  private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);

  private final FileWal wal;
  private final ClickHouseBatchWriter writer;
  private final RecordDecoder decoder;
  private final IngestMetrics metrics;
  private final RealtimePublisher publisher;
  private final Clock clock;
  private final Map<Table, Long> skipThrough = new EnumMap<>(Table.class);
  private final AtomicLong recordsLoaded = new AtomicLong();
  private volatile boolean recovering;
  private volatile boolean stopping;
  private volatile boolean dispatcherStopped;
  private volatile DurableMark processed = DurableMark.NONE;
  private volatile Thread dispatcher;
  private ExecutorService decodePool;
  private WalGc gc;
  private long gcPeriodMs = 60_000;

  /**
   * The bean of the server. The real-time port is WO-22's {@code RealtimeBus} when that bean exists
   * and {@link RealtimePublisher#noop()} until then (ADR-0010 C-128).
   */
  @Autowired
  public IngestService(
      FileWal wal,
      ClickHouseBatchWriter writer,
      RecordDecoder decoder,
      IngestMetrics metrics,
      ObjectProvider<RealtimePublisher> publishers) {
    this(
        wal,
        writer,
        decoder,
        metrics,
        publishers.getIfUnique(RealtimePublisher::noop),
        Clock.systemUTC());
  }

  /** A service with an explicit clock (tests). */
  public IngestService(
      FileWal wal,
      ClickHouseBatchWriter writer,
      RecordDecoder decoder,
      IngestMetrics metrics,
      RealtimePublisher publisher,
      Clock clock) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.decoder = Objects.requireNonNull(decoder, "decoder");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.clock = clock;
  }

  /** The WAL GC period (60 s by default, 12 §3.4); tests shorten it before {@link #recover}. */
  public void setGcPeriodMs(long ms) {
    gcPeriodMs = ms;
  }

  // --- IngestPipeline -----------------------------------------------------------------------

  @Override
  public void onTransactions(SessionContext session, WalPosition position, TransactionBatch batch) {
    // Durable already: the dispatcher reads the record from the WAL (same bytes on every path).
  }

  @Override
  public void onProfiles(SessionContext session, WalPosition position, ProfileBatch batch) {
    // See onTransactions.
  }

  @Override
  public void onMetrics(SessionContext session, WalPosition position, MetricBatch batch) {
    // See onTransactions.
  }

  @Override
  public void onStats(SessionContext session, WalPosition position, StatBatch batch) {
    // See onTransactions.
  }

  @Override
  public void onActive(SessionContext session, ActiveTransactionSnapshot snapshot) {
    publisher.publishActive(session, snapshot);
  }

  // --- recovery -----------------------------------------------------------------------------

  /**
   * Recovery after the scan (12 §3.4): re-inserts the uncommitted intents with their tokens, sets
   * what is covered, starts the dispatcher on the oldest segment and waits until every old record
   * is committed. The WAL and the writer must be started.
   */
  public void recover(WalRecovery.Scan scan) throws IOException, InterruptedException {
    recovering = true;
    long started = clock.millis();
    List<WalRecovery.FoundIntent> uncommitted = scan.uncommitted();
    int n = 0;
    for (WalRecovery.FoundIntent found : uncommitted) {
      BlockIntent intent = found.intent();
      List<EncodedRows> rows = rebuild(intent);
      writer.reinsert(intent, rows);
      n++;
      LOG.info(
          "WAL recovery: block {} of {} sent again with its token ({} of {})",
          intent.token(),
          intent.table().tableName(),
          n,
          uncommitted.size());
    }
    for (WalRecovery.FoundIntent found : scan.intents()) {
      Table table = found.intent().table();
      long last = found.intent().last().packed();
      skipThrough.merge(table, last, (a, b) -> Long.compareUnsigned(a, b) >= 0 ? a : b);
    }
    for (Map.Entry<Table, Long> covered : skipThrough.entrySet()) {
      writer.initCoverage(covered.getKey(), covered.getValue());
    }
    int firstSegment = scan.firstSegmentId() >= 0 ? scan.firstSegmentId() : wal.activeSegmentId();
    DurableMark end = wal.durableMark();
    startDispatcher(firstSegment);
    long lastLog = clock.millis();
    while (!isProcessedThrough(end)) {
      if (stopping) {
        throw new InterruptedException("Stopped during WAL recovery");
      }
      if (dispatcherStopped) {
        // The start fails instead of waiting forever; the records stay in the WAL.
        throw new IOException(
            "The ingest dispatcher stopped during WAL recovery (see the error above); the"
                + " records stay in the WAL and are loaded after a restart");
      }
      Thread.sleep(50);
      long now = clock.millis();
      if (now - lastLog >= PROGRESS_INTERVAL_MS) {
        lastLog = now;
        LOG.info(
            "WAL recovery: {} records loaded so far, reading {}",
            recordsLoaded.get(),
            WalFormat.segmentName(currentSegment()));
      }
    }
    writer.flushNow();
    while (!writer.awaitIdle(PROGRESS_INTERVAL_MS)) {
      LOG.info("WAL recovery: waiting for ClickHouse to accept the recovered blocks");
      if (stopping) {
        throw new InterruptedException("Stopped during WAL recovery");
      }
    }
    recovering = false;
    gc = new WalGc(wal, writer);
    gc.start(gcPeriodMs);
    LOG.info(
        "WAL recovery finished in {} ms: {} blocks sent again, {} records loaded",
        clock.millis() - started,
        uncommitted.size(),
        recordsLoaded.get());
  }

  /**
   * The rows of an intent's table from the records in its range (12 §3.4). A record whose decode
   * throws is skipped, as the live path skipped it ({@link #deliver}), so the rebuilt block has the
   * rows of the first insert.
   */
  private List<EncodedRows> rebuild(BlockIntent intent) throws IOException {
    List<EncodedRows> rows = new ArrayList<>();
    Set<Table> only = EnumSet.of(intent.table());
    WalTailReader.readRange(
        wal.directory(),
        intent.first(),
        intent.last(),
        record -> {
          if (record.type().isData()
              && ClickHouseBatchWriter.tablesOf(record.type()).contains(intent.table())) {
            EncodedRows r;
            try {
              r = decoder.decode(record, only, false).rows().get(intent.table());
            } catch (RuntimeException e) {
              metrics.error("decode");
              LOG.warn(
                  "WAL recovery: decoding the record at {} failed ({}); it is skipped as on its"
                      + " first load",
                  record.position(),
                  e.getClass().getName());
              r = null;
            }
            if (r != null) {
              rows.add(r);
            }
          }
        });
    return rows;
  }

  /** Whether recovery is running. */
  public boolean isRecovering() {
    return recovering;
  }

  /**
   * Whether every record before {@code mark} has been delivered to the writer (or skipped): with
   * {@link ClickHouseBatchWriter#awaitIdle} after it, everything durable at {@code mark} is in
   * ClickHouse.
   */
  public boolean isProcessedThrough(DurableMark mark) {
    return processed.compareTo(mark) >= 0;
  }

  /** The WAL GC (after recovery), or {@code null}. */
  public WalGc gc() {
    return gc;
  }

  /** Data records decoded and delivered since the start. */
  public long recordsLoaded() {
    return recordsLoaded.get();
  }

  /**
   * Whether the dispatcher ended without being stopped (an unreadable WAL record, an unexpected
   * exception): nothing more is loaded into ClickHouse until a restart, while the collector keeps
   * appending to the WAL. {@link IngestHealthIndicator} reports it as DOWN; there is no restart in
   * the process (a second dispatcher would deliver records again in new blocks).
   */
  public boolean isDispatcherStopped() {
    return dispatcherStopped;
  }

  // --- dispatcher ---------------------------------------------------------------------------

  private volatile int readingSegment;

  private int currentSegment() {
    return readingSegment;
  }

  private synchronized void startDispatcher(int firstSegment) {
    if (dispatcher != null) {
      return;
    }
    AtomicInteger n = new AtomicInteger();
    ThreadFactory factory =
        r -> {
          Thread t = new Thread(r, "argos-ingest-" + n.incrementAndGet());
          t.setDaemon(true);
          return t;
        };
    decodePool = Executors.newFixedThreadPool(DECODE_THREADS, factory);
    Thread t = new Thread(() -> dispatch(firstSegment), "argos-ingest-dispatch");
    t.setDaemon(false);
    dispatcher = t;
    t.start();
  }

  private record InFlight(
      WalRecord record, Set<Table> tables, CompletableFuture<RecordDecoder.Decoded> decoded) {}

  private void dispatch(int firstSegment) {
    ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
    try (WalTailReader reader =
        new WalTailReader(wal, firstSegment, WalFormat.SEGMENT_HEADER_BYTES)) {
      while (!stopping) {
        readingSegment = reader.nextSegmentId();
        // Deliver what is decoded, in order.
        while (!inFlight.isEmpty()
            && (inFlight.peekFirst().decoded().isDone() || inFlight.size() >= MAX_IN_FLIGHT)) {
          if (!deliver(inFlight.pollFirst())) {
            return;
          }
        }
        WalRecord record = reader.next(inFlight.isEmpty() ? 200 : 0);
        if (record == null) {
          if (inFlight.isEmpty()) {
            if (reader.atDurableEnd()) {
              processed = new DurableMark(reader.nextSegmentId(), reader.nextOffset());
            }
          } else {
            InFlight head = inFlight.peekFirst();
            try {
              head.decoded().get(5, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
              // delivered (or failed) on the next turn
            }
          }
          continue;
        }
        Set<Table> tables = EnumSet.noneOf(Table.class);
        if (record.type().isData()) {
          long packed = record.position().packed();
          for (Table table : ClickHouseBatchWriter.tablesOf(record.type())) {
            Long covered = skipThrough.get(table);
            if (covered == null || Long.compareUnsigned(packed, covered) > 0) {
              tables.add(table);
            }
          }
        }
        if (tables.isEmpty()) {
          // Block records and records already covered: nothing to decode, kept in order.
          inFlight.add(new InFlight(record, tables, CompletableFuture.completedFuture(null)));
          continue;
        }
        CompletableFuture<RecordDecoder.Decoded> future =
            CompletableFuture.supplyAsync(() -> decoder.decode(record, tables, true), decodePool);
        inFlight.add(new InFlight(record, tables, future));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException e) {
      LOG.error(
          "The ingest dispatcher stopped: {}. Data stays in the WAL and is loaded after a restart",
          e.toString(),
          e);
    } finally {
      // The loop ends only when stopping: any other end is a failure (exception, error, interrupt).
      if (!stopping) {
        dispatcherStopped = true;
        metrics.error("dispatch");
      }
    }
  }

  /** Hands one decoded record to the writer; {@code false} when stopping. */
  private boolean deliver(InFlight item) throws InterruptedException {
    if (item.tables().isEmpty()) {
      markProcessed(item.record());
      return true;
    }
    RecordDecoder.Decoded decoded;
    try {
      decoded = item.decoded().get();
    } catch (ExecutionException e) {
      // 12 §3.9: the record is skipped and counted; its tables get markers, the WAL moves on.
      metrics.error("decode");
      LOG.warn(
          "Decoding the record at {} failed: {}",
          item.record().position(),
          e.getCause().toString());
      decoded = RecordDecoder.Decoded.EMPTY;
    }
    WalRecord record = item.record();
    for (Table table : item.tables()) {
      EncodedRows rows = decoded.rows().get(table);
      while (!writer.offer(table, record.position(), rows, record.receivedMs(), OFFER_SLICE_MS)) {
        if (stopping) {
          return false;
        }
      }
    }
    recordsLoaded.incrementAndGet();
    markProcessed(record);
    if (!recovering
        && decoded.session() != null
        && !decoded.points().isEmpty()
        && clock.millis() - record.receivedMs() <= LIVE_WINDOW_MS) {
      try {
        publisher.publishXlog(decoded.session(), decoded.points());
      } catch (RuntimeException e) {
        LOG.debug("Realtime publish failed: {}", e.toString());
      }
    }
    return true;
  }

  private void markProcessed(WalRecord record) {
    processed = new DurableMark(record.position().segmentId(), record.position().end());
  }

  // --- shutdown -----------------------------------------------------------------------------

  /** Stops the dispatcher, the decode pool and the WAL GC. */
  public void close() {
    stopping = true;
    WalGc g = gc;
    if (g != null) {
      g.close();
    }
    Thread t = dispatcher;
    if (t != null) {
      try {
        t.join(5_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (t.isAlive()) {
        t.interrupt();
      }
    }
    ExecutorService pool = decodePool;
    if (pool != null) {
      pool.shutdownNow();
    }
    if (t != null) {
      // 12 §3.9: records the dispatcher has not delivered stay in the WAL and load at the next
      // start.
      LOG.info("Ingest dispatcher stopped after {} records loaded", recordsLoaded.get());
    }
  }

  @Override
  public void destroy() {
    close();
  }
}
