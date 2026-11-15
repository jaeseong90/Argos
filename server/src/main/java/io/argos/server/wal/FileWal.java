/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.config.ArgosProperties;
import io.argos.server.wal.api.WalAppendPendingException;
import io.argos.server.wal.api.WalBusyException;
import io.argos.server.wal.api.WalFullException;
import io.argos.server.wal.api.WalIdentity;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriteException;
import io.argos.server.wal.api.WalWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The file write-ahead log (12 §3.4, DSN-12-01, DSN-12-02): one {@code argos-wal-writer} thread
 * appends the records of all callers and makes them durable with group fsync ({@code
 * FileChannel.force(false)}) at most every {@code argos.wal.fsync-interval-ms} (10 ms) or after 4
 * MiB of waiting records; {@link #append} returns a position only after the fsync of its group
 * (D-13).
 *
 * <ul>
 *   <li>Segments {@code seg-<8 digits>.wal} rotate at {@code argos.wal.segment-bytes} (256 MiB); a
 *       record larger than that gets an empty segment of its own. Every start opens a new segment;
 *       older segments are only read (recovery, replay) and deleted by {@link WalGc}.
 *   <li>Data records above {@code argos.wal.max-bytes} (10 GiB) are refused with {@link
 *       WalFullException}; block records are always accepted (they are small and needed to free
 *       space). Crossing {@code argos.wal.warn-ratio} (0.8) logs one WARN and counts {@code
 *       argos_wal_warnings_total}; the warning re-arms below the ratio.
 *   <li>An I/O error of a write or fsync fails the group with {@link WalWriteException} and stops
 *       the WAL (fail-stop: after a failed fsync nothing is known about the file); every later
 *       append fails until a restart, whose recovery cuts a torn tail.
 *   <li>A data append is refused at once with {@link WalBusyException} (nothing queued) while a
 *       group write and fsync has been running for longer than the data append wait, or when the
 *       data records waiting in the queue would exceed {@link #MAX_QUEUED_DATA_BYTES} (64 MiB): a
 *       stuck disk neither holds executor threads nor piles up batches in memory. Otherwise it
 *       waits at most {@link #DATA_APPEND_WAIT} (1 s, 11 §10) for room in the queue ({@link
 *       WalWriteException}: nothing queued) and as long again for its group fsync ({@link
 *       WalAppendPendingException}: queued, may still become durable, with the record's future).
 *       Block records of the batch writer (intents, commits) are never refused as busy and wait up
 *       to {@link #BLOCK_APPEND_TIMEOUT}.
 *   <li>Memory of records that are not yet durable while an fsync is stuck: at most {@link
 *       #MAX_QUEUED_DATA_BYTES} of waiting data records, plus the group the writer holds (at most
 *       {@link #GROUP_BYTES} plus one record), plus the small block records. Outside the WAL, each
 *       of the 8 gRPC executor threads ({@code GrpcCollectorServer.EXECUTOR_THREADS}) holds the
 *       batch of its call while it appends: the decoded message (at least its serialized size), the
 *       kept batch's bytes, the {@code WalDataPayload} encoding and the encoded record ({@link
 *       WalFormat#encode}), about 3 × {@code argos.collector.max-message-bytes} of serialized
 *       copies per call. With the default 4 MiB that is about 72 MiB in the WAL plus about 98 MiB
 *       of executor copies plus at least 32 MiB of decoded batches, about 200 MiB in all; with the
 *       largest setting of 63 MiB about 131 MiB, 1.5 GiB and 0.5 GiB, about 2.1 GiB (WO-21 report,
 *       N-2). The executor part does not depend on a stuck fsync (eight calls of the largest
 *       message at once); while the fsync is stuck {@link WalBusyException} frees the executor
 *       threads at once.
 * </ul>
 *
 * <p>Metrics: {@code argos_wal_bytes}, {@code argos_wal_segments}, {@code argos_wal_fsync_seconds}
 * (histogram), {@code argos_wal_usage_ratio}, {@code argos_wal_warnings_total}, {@code
 * argos_wal_queued_bytes} (data bytes waiting in the writer queue, at most {@link
 * #MAX_QUEUED_DATA_BYTES}; ADR-0010 C-157 (6)).
 *
 * <p>The bean does no I/O until {@link #start} (the {@code WAL_RECOVERY} step, after the scan of
 * the old segments); appends before that fail with {@link WalWriteException}.
 */
@Component
public final class FileWal implements WalWriter, DisposableBean {

  /** Bytes of waiting records that trigger an fsync before the interval ends (12 §3.4). */
  static final long GROUP_BYTES = 4L * 1024 * 1024;

  /**
   * Bytes of data records that may wait in the writer queue (16 groups, 64 MiB); a data append that
   * would exceed it is refused at once with {@link WalBusyException}. It bounds the memory of
   * batches that are not yet durable while an fsync is stuck (12 §11), and so the number of batches
   * that may still be stored after their agent gave up on them. A record of the largest message
   * ({@code argos.collector.max-message-bytes} is at most {@code
   * ArgosProperties.Collector.MAX_MESSAGE_BYTES_UPPER}) always fits into an empty queue.
   */
  static final long MAX_QUEUED_DATA_BYTES = 16 * GROUP_BYTES;

  /**
   * Largest {@code argos.wal.segment-bytes}, 4 GiB: records start below the segment size, and a
   * {@link WalPosition} offset is at most {@link WalPosition#MAX_OFFSET}.
   */
  public static final long MAX_SEGMENT_BYTES = WalPosition.MAX_OFFSET + 1;

  /**
   * How long a data append waits for room in the writer queue, and again for its group fsync: 11
   * §10 answers {@code UNAVAILABLE} when the WAL fsync takes longer than 1 s, and a gRPC executor
   * thread is held for about that long at most (12 §11).
   */
  public static final Duration DATA_APPEND_WAIT = Duration.ofSeconds(1);

  /**
   * Upper bound of one block-record append wait (the batch writer's threads, which retry an intent
   * or commit with the same token); a writer that is stuck that long counts as failed.
   */
  static final Duration BLOCK_APPEND_TIMEOUT = Duration.ofMinutes(2);

  private static final Logger LOG = LoggerFactory.getLogger(FileWal.class);

  private final Path dirPath;
  private final long segmentBytes;
  private final long maxBytes;
  private final long fsyncIntervalNanos;
  private final double warnRatio;
  private final Clock clock;
  private final long dataAppendWaitNanos;
  private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>(65_536);
  private final NavigableMap<Integer, SegmentInfo> segments = new ConcurrentSkipListMap<>();
  private final AtomicLong bytesInUse = new AtomicLong();
  // Bytes of the data records in the queue: reserved before the offer, released when the writer
  // takes the record (or a refused offer undoes the reservation). Never above
  // MAX_QUEUED_DATA_BYTES.
  private final AtomicLong queuedDataBytes = new AtomicLong();
  private final Object durableMonitor = new Object();
  private final Timer fsyncTimer;
  private final Counter warnings;

  private volatile WalDirectory directory;
  private volatile Thread writerThread;
  private volatile boolean stopping;
  private volatile IOException failure;
  private volatile DurableMark durable = DurableMark.NONE;
  private volatile boolean warned;
  private volatile Runnable beforeFsync;
  // System.nanoTime() when the writer began writing and fsyncing its current group, 0 while it is
  // not in a group write.
  private volatile long groupBusySinceNanos;

  // Writer thread state.
  private FileChannel channel;
  private SegmentInfo active;
  private long lastFsyncStartNanos;

  /** The bean of the server: settings {@code argos.wal.*}. */
  @Autowired
  public FileWal(ArgosProperties properties, MeterRegistry registry) {
    this(
        Path.of(properties.wal().dir()),
        properties.wal().segmentBytes().toBytes(),
        properties.wal().maxBytes().toBytes(),
        properties.wal().fsyncIntervalMs(),
        properties.wal().warnRatio(),
        registry,
        Clock.systemUTC());
  }

  /**
   * A WAL with explicit settings and the data append wait of {@link #DATA_APPEND_WAIT} (tests).
   *
   * @param dir {@code argos.wal.dir}
   * @param segmentBytes {@code argos.wal.segment-bytes}
   * @param maxBytes {@code argos.wal.max-bytes}
   * @param fsyncIntervalMs {@code argos.wal.fsync-interval-ms}
   * @param warnRatio {@code argos.wal.warn-ratio}
   * @param registry meter registry
   * @param clock wall clock of segment headers and block records
   */
  public FileWal(
      Path dir,
      long segmentBytes,
      long maxBytes,
      int fsyncIntervalMs,
      double warnRatio,
      MeterRegistry registry,
      Clock clock) {
    this(
        dir, segmentBytes, maxBytes, fsyncIntervalMs, warnRatio, registry, clock, DATA_APPEND_WAIT);
  }

  /**
   * A WAL with explicit settings and a data append wait (unit tests scale the 1 s of 11 §10 down,
   * ADR-0010 C-146 (2)).
   *
   * @param dataAppendWait how long a data append waits for queue room and again for its fsync, and
   *     how long a group write and fsync may run before data appends are refused as busy ({@link
   *     #DATA_APPEND_WAIT} in the server)
   */
  public FileWal(
      Path dir,
      long segmentBytes,
      long maxBytes,
      int fsyncIntervalMs,
      double warnRatio,
      MeterRegistry registry,
      Clock clock,
      Duration dataAppendWait) {
    if (dataAppendWait.isNegative() || dataAppendWait.isZero()) {
      throw new IllegalArgumentException(
          "The data append wait must be positive: " + dataAppendWait);
    }
    if (segmentBytes < WalFormat.SEGMENT_HEADER_BYTES + WalFormat.recordLength(0)) {
      throw new IllegalArgumentException("argos.wal.segment-bytes too small: " + segmentBytes);
    }
    if (segmentBytes > MAX_SEGMENT_BYTES) {
      // A record offset must fit the 32 bits of a WalPosition (C-61 tokens pack it).
      throw new IllegalArgumentException(
          "argos.wal.segment-bytes ("
              + segmentBytes
              + ") is above 4 GiB ("
              + MAX_SEGMENT_BYTES
              + " bytes), the largest segment a WAL position can address");
    }
    if (maxBytes < segmentBytes) {
      throw new IllegalArgumentException(
          "argos.wal.max-bytes ("
              + maxBytes
              + ") below argos.wal.segment-bytes ("
              + segmentBytes
              + ")");
    }
    this.dirPath = dir;
    this.segmentBytes = segmentBytes;
    this.maxBytes = maxBytes;
    this.fsyncIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, fsyncIntervalMs));
    this.warnRatio = warnRatio;
    this.clock = clock;
    this.dataAppendWaitNanos = dataAppendWait.toNanos();
    Gauge.builder("argos.wal.bytes", bytesInUse, AtomicLong::get)
        .baseUnit("bytes")
        .description("Bytes of all WAL segments on disk")
        .register(registry);
    Gauge.builder("argos.wal.segments", segments, Map::size)
        .description("WAL segment files on disk")
        .register(registry);
    Gauge.builder("argos.wal.usage.ratio", this, FileWal::usageRatio)
        .description("WAL bytes / argos.wal.max-bytes")
        .register(registry);
    Gauge.builder("argos.wal.queued.bytes", queuedDataBytes, AtomicLong::get)
        .baseUnit("bytes")
        .description(
            "Bytes of data records waiting in the WAL writer queue (not yet taken for a group"
                + " write); at most 64 MiB, above it data appends are refused as busy")
        .register(registry);
    this.fsyncTimer =
        Timer.builder("argos.wal.fsync")
            .description("Duration of one WAL group fsync")
            .publishPercentileHistogram()
            .register(registry);
    this.warnings =
        Counter.builder("argos.wal.warnings")
            .description("Times the WAL crossed argos.wal.warn-ratio")
            .register(registry);
  }

  /** {@code argos.wal.dir} as configured. */
  public Path configuredDirectory() {
    return dirPath;
  }

  /**
   * Opens a new segment after the existing ones and starts the writer thread. Called once by
   * recovery, after the old segments were scanned and their torn tails cut.
   *
   * @param dir the opened directory
   * @param existing the remaining old segments with their summaries (from the scan)
   * @throws IOException when the new segment cannot be created
   */
  public synchronized void start(WalDirectory dir, List<SegmentInfo> existing) throws IOException {
    if (writerThread != null) {
      throw new IllegalStateException("The WAL is already started");
    }
    this.directory = dir;
    int nextId = 0;
    long total = 0;
    for (SegmentInfo info : existing) {
      info.close();
      segments.put(info.segmentId(), info);
      total += info.size();
      nextId = Math.max(nextId, info.segmentId() + 1);
    }
    bytesInUse.set(total);
    openSegment(nextId);
    durable = new DurableMark(active.segmentId(), active.size());
    Thread thread = new Thread(this::run, "argos-wal-writer");
    thread.setDaemon(false);
    writerThread = thread;
    thread.start();
    LOG.info(
        "WAL started in {}: identity {} epoch {}, segment {}, {} bytes in {} segments",
        dir.path(),
        dir.identity().walInstanceId(),
        dir.identity().epoch(),
        WalFormat.segmentName(active.segmentId()),
        total + active.size(),
        segments.size());
  }

  /** Whether {@link #start} ran. */
  public boolean isStarted() {
    return writerThread != null;
  }

  /**
   * Test seam: {@code hook} runs on the writer thread before every group fsync of the running WAL
   * (a hook that blocks plays a stuck disk); {@code null} removes it. Never set by the server.
   */
  void beforeFsyncForTest(Runnable hook) {
    this.beforeFsync = hook;
  }

  /** Bytes of the data records waiting in the writer queue (not yet taken by the writer). */
  long queuedDataBytes() {
    return queuedDataBytes.get();
  }

  /** Records (data and block) waiting in the writer queue (not yet taken by the writer). */
  int queueSize() {
    return queue.size();
  }

  // --- WalWriter -----------------------------------------------------------------------------

  @Override
  public WalPosition append(
      WalRecordType type,
      int instanceId,
      long agentEpoch,
      long seq,
      long receivedMs,
      long tsCorrectionMs,
      byte[] payload)
      throws WalFullException, WalBusyException, WalWriteException, WalAppendPendingException {
    if (!type.isData()) {
      throw new IllegalArgumentException("Not a data record type: " + type);
    }
    Pending pending =
        enqueue(
            type,
            WalFormat.encode(
                type, instanceId, agentEpoch, seq, receivedMs, tsCorrectionMs, payload),
            dataAppendWaitNanos);
    try {
      return await(pending, dataAppendWaitNanos);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WalAppendPendingException(
          "Interrupted while waiting for the WAL fsync; the record is queued",
          pending.future.minimalCompletionStage());
    } catch (TimeoutException e) {
      throw new WalAppendPendingException(
          "No WAL fsync within " + TimeUnit.NANOSECONDS.toMillis(dataAppendWaitNanos) + " ms",
          pending.future.minimalCompletionStage());
    }
  }

  @Override
  public WalPosition appendBlockIntent(byte[] payload) throws WalWriteException {
    return appendBlock(WalRecordType.BLOCK_INTENT, payload);
  }

  @Override
  public WalPosition appendBlockCommit(byte[] payload) throws WalWriteException {
    return appendBlock(WalRecordType.BLOCK_COMMIT, payload);
  }

  private WalPosition appendBlock(WalRecordType type, byte[] payload) throws WalWriteException {
    long waitNanos = BLOCK_APPEND_TIMEOUT.toNanos();
    Pending pending;
    try {
      pending =
          enqueue(type, WalFormat.encode(type, 0, 0, 0, clock.millis(), 0, payload), waitNanos);
    } catch (WalBusyException e) {
      throw new IllegalStateException("Block records are never refused as busy", e);
    }
    try {
      return await(pending, waitNanos);
    } catch (WalFullException e) {
      throw new IllegalStateException("Block records are never refused", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WalWriteException("Interrupted while waiting for the WAL fsync", e);
    } catch (TimeoutException e) {
      throw new WalWriteException(
          "No WAL fsync within " + BLOCK_APPEND_TIMEOUT.toSeconds() + " s", e);
    }
  }

  /**
   * Puts one encoded record in the writer queue, waiting at most {@code waitNanos} for room. A data
   * record is refused at once ({@link WalBusyException}) while the writer is stuck in a group or
   * when the waiting data records would exceed {@link #MAX_QUEUED_DATA_BYTES}; block records never
   * are. When this throws, nothing was queued: the record will never be written.
   */
  private Pending enqueue(WalRecordType type, byte[] bytes, long waitNanos)
      throws WalWriteException, WalBusyException {
    if (writerThread == null) {
      throw new WalWriteException("The WAL is not started (recovery has not run)", null);
    }
    IOException failed = failure;
    if (failed != null) {
      throw new WalWriteException("The WAL failed earlier and accepts no record", failed);
    }
    if (stopping) {
      throw new WalWriteException("The WAL is stopping", null);
    }
    boolean data = type.isData();
    if (data) {
      reserveDataBytes(bytes.length);
    }
    Pending pending = new Pending(type, bytes);
    boolean queued = false;
    try {
      queued = queue.offer(pending, waitNanos, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WalWriteException("Interrupted while waiting for room in the WAL queue", e);
    } finally {
      if (!queued && data) {
        queuedDataBytes.addAndGet(-bytes.length);
      }
    }
    if (!queued) {
      throw new WalWriteException("The WAL writer queue is full", null);
    }
    return pending;
  }

  /**
   * Reserves {@code length} bytes of waiting data records, or refuses the record without queuing
   * it: while the current group write and fsync has been running for longer than the data append
   * wait (11 §10), or when the reservation would exceed {@link #MAX_QUEUED_DATA_BYTES}.
   */
  private void reserveDataBytes(int length) throws WalBusyException {
    long busySince = groupBusySinceNanos;
    if (busySince != 0) {
      long busyNanos = System.nanoTime() - busySince;
      if (busyNanos > dataAppendWaitNanos) {
        throw new WalBusyException(
            "A WAL group fsync has been running for "
                + TimeUnit.NANOSECONDS.toMillis(busyNanos)
                + " ms, longer than the "
                + TimeUnit.NANOSECONDS.toMillis(dataAppendWaitNanos)
                + " ms append wait; the record was not queued");
      }
    }
    while (true) {
      long queued = queuedDataBytes.get();
      if (queued + length > MAX_QUEUED_DATA_BYTES) {
        throw new WalBusyException(
            "Data records waiting for the WAL writer would exceed "
                + MAX_QUEUED_DATA_BYTES
                + " bytes; the record was not queued");
      }
      if (queuedDataBytes.compareAndSet(queued, queued + length)) {
        return;
      }
    }
  }

  /** The writer took {@code p} from the queue: its bytes no longer count as waiting. */
  private void taken(Pending p) {
    if (p.type.isData()) {
      queuedDataBytes.addAndGet(-p.bytes.length);
    }
  }

  /**
   * Waits at most {@code waitNanos} for the group fsync of a queued record. A timeout or an
   * interrupt leaves the record queued: it may still become durable.
   */
  private static WalPosition await(Pending pending, long waitNanos)
      throws WalFullException, WalWriteException, InterruptedException, TimeoutException {
    try {
      return pending.future.get(waitNanos, TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof WalFullException full) {
        throw full;
      }
      if (cause instanceof WalWriteException write) {
        throw write;
      }
      throw new WalWriteException("WAL write failed", cause);
    }
  }

  @Override
  public WalIdentity identity() {
    WalDirectory dir = directory;
    if (dir == null) {
      throw new IllegalStateException("The WAL is not started");
    }
    return dir.identity();
  }

  @Override
  public long bytesInUse() {
    return bytesInUse.get();
  }

  @Override
  public long maxBytes() {
    return maxBytes;
  }

  // --- readers and GC -----------------------------------------------------------------------

  /** The durable end of the WAL. */
  public DurableMark durableMark() {
    return durable;
  }

  /**
   * Waits until the durable end is beyond {@code seen} or {@code timeoutMs} passed.
   *
   * @return the current durable mark
   */
  public DurableMark awaitDurableBeyond(DurableMark seen, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    synchronized (durableMonitor) {
      while (durable.compareTo(seen) <= 0) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          break;
        }
        TimeUnit.NANOSECONDS.timedWait(durableMonitor, left);
      }
      return durable;
    }
  }

  /** The directory, once started. */
  public WalDirectory directory() {
    WalDirectory dir = directory;
    if (dir == null) {
      throw new IllegalStateException("The WAL is not started");
    }
    return dir;
  }

  /** Summaries of every segment on disk, ascending (the active one last). */
  public List<SegmentInfo> segments() {
    return new ArrayList<>(segments.values());
  }

  /** The id of the segment the writer appends to, or -1 before the start. */
  public int activeSegmentId() {
    SegmentInfo current = active;
    return current == null ? -1 : current.segmentId();
  }

  /**
   * Deletes a closed segment (GC). The caller guarantees that nothing in it is still needed.
   *
   * @throws IllegalArgumentException for the active segment or one that is not the oldest
   */
  public void deleteSegment(int segmentId) throws IOException {
    SegmentInfo info = segments.get(segmentId);
    if (info == null) {
      return;
    }
    if (!info.isClosed()) {
      throw new IllegalArgumentException("Segment " + segmentId + " is active");
    }
    if (segments.firstKey() != segmentId) {
      throw new IllegalArgumentException("Segment " + segmentId + " is not the oldest");
    }
    directory().deleteSegment(segmentId);
    segments.remove(segmentId);
    long now = bytesInUse.addAndGet(-info.size());
    directory().syncDirectory();
    if (warned && usageRatio(now) < warnRatio) {
      warned = false;
    }
  }

  // --- writer thread ------------------------------------------------------------------------

  private void run() {
    List<Pending> group = new ArrayList<>();
    try {
      while (true) {
        Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
        if (first == null) {
          if (stopping) {
            break;
          }
          continue;
        }
        taken(first);
        group.add(first);
        long groupBytes = first.bytes.length;
        long deadline = lastFsyncStartNanos + fsyncIntervalNanos;
        while (groupBytes < GROUP_BYTES) {
          Pending next = queue.poll();
          if (next == null) {
            long wait = deadline - System.nanoTime();
            if (wait <= 0 || stopping) {
              break;
            }
            next = queue.poll(wait, TimeUnit.NANOSECONDS);
            if (next == null) {
              break;
            }
          }
          taken(next);
          group.add(next);
          groupBytes += next.bytes.length;
        }
        writeGroup(group);
        group.clear();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException | Error e) {
      LOG.error("The WAL writer thread failed: {}", e.toString(), e);
      if (failure == null) {
        failure = new IOException("WAL writer thread failed: " + e, e);
      }
    } finally {
      failRemaining(group);
      closeChannel();
    }
  }

  private void writeGroup(List<Pending> group) {
    IOException failed = failure;
    if (failed != null) {
      failAll(group, failed);
      return;
    }
    List<Pending> written = new ArrayList<>(group.size());
    long busySince = System.nanoTime();
    groupBusySinceNanos = busySince != 0 ? busySince : 1; // 0 means "not in a group write"
    try {
      for (Pending p : group) {
        int length = p.bytes.length;
        long inUse = bytesInUse.get();
        if (p.type.isData() && inUse + length > maxBytes) {
          p.future.completeExceptionally(new WalFullException(inUse, maxBytes));
          continue;
        }
        if (active.size() + length > segmentBytes
            && active.size() > WalFormat.SEGMENT_HEADER_BYTES) {
          rotate();
        }
        long offset = active.size();
        ByteBuffer buffer = ByteBuffer.wrap(p.bytes);
        long at = offset;
        while (buffer.hasRemaining()) {
          at += channel.write(buffer, at);
        }
        active.grow(length);
        bytesInUse.addAndGet(length);
        WalPosition position = new WalPosition(active.segmentId(), offset, length);
        active.recordWritten(p.type, position);
        p.position = position;
        written.add(p);
      }
      if (!written.isEmpty()) {
        force();
      }
    } catch (IOException e) {
      LOG.error(
          "WAL write or fsync failed in {}; the WAL stops accepting records until a restart: {}",
          active == null ? dirPath : directory.segmentFile(active.segmentId()),
          e.toString());
      failure = e;
      failAll(group, e);
      return;
    } finally {
      // Cleared before the futures of the group complete: a caller whose append just returned
      // never finds this group still counted as stuck.
      groupBusySinceNanos = 0;
    }
    DurableMark mark = new DurableMark(active.segmentId(), active.size());
    synchronized (durableMonitor) {
      durable = mark;
      durableMonitor.notifyAll();
    }
    for (Pending p : written) {
      p.future.complete(p.position);
    }
    checkWarn();
  }

  private void force() throws IOException {
    Runnable hook = beforeFsync;
    if (hook != null) {
      hook.run();
    }
    lastFsyncStartNanos = System.nanoTime();
    long start = lastFsyncStartNanos;
    channel.force(false);
    fsyncTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
  }

  private void rotate() throws IOException {
    force();
    channel.close();
    channel = null;
    active.close();
    openSegment(active.segmentId() + 1);
  }

  private void openSegment(int segmentId) throws IOException {
    FileChannel created = directory.createSegment(segmentId);
    try {
      ByteBuffer header = WalFormat.segmentHeader(clock.millis());
      long at = 0;
      while (header.hasRemaining()) {
        at += created.write(header, at);
      }
      created.force(false);
    } catch (IOException e) {
      created.close();
      throw e;
    }
    directory.syncDirectory();
    channel = created;
    SegmentInfo info = new SegmentInfo(segmentId, WalFormat.SEGMENT_HEADER_BYTES);
    segments.put(segmentId, info);
    bytesInUse.addAndGet(WalFormat.SEGMENT_HEADER_BYTES);
    active = info;
  }

  private void checkWarn() {
    double ratio = usageRatio();
    if (!warned && ratio >= warnRatio) {
      warned = true;
      warnings.increment();
      LOG.warn(
          "WAL usage {}% of argos.wal.max-bytes ({} of {} bytes): ClickHouse inserts are behind;"
              + " at 100% batches are dropped (wal_full)",
          Math.round(ratio * 100), bytesInUse.get(), maxBytes);
    } else if (warned && ratio < warnRatio) {
      warned = false;
    }
  }

  private double usageRatio(long inUse) {
    return maxBytes <= 0 ? 1.0 : (double) inUse / maxBytes;
  }

  private void failAll(List<Pending> group, Exception cause) {
    for (Pending p : group) {
      if (!p.future.isDone()) {
        p.future.completeExceptionally(
            new WalWriteException("WAL write or fsync failed: " + cause.getMessage(), cause));
      }
    }
  }

  private void failRemaining(List<Pending> group) {
    WalWriteException stopped = new WalWriteException("The WAL is stopped", failure);
    for (Pending p : group) {
      if (!p.future.isDone()) {
        p.future.completeExceptionally(stopped);
      }
    }
    Pending p;
    while ((p = queue.poll()) != null) {
      taken(p);
      p.future.completeExceptionally(stopped);
    }
  }

  private void closeChannel() {
    if (channel != null) {
      try {
        if (failure == null) {
          channel.force(false);
        }
        channel.close();
      } catch (IOException e) {
        LOG.warn("Closing the WAL segment failed: {}", e.toString());
      }
      channel = null;
    }
  }

  /**
   * Stops accepting records, writes and fsyncs what is queued, and stops the writer thread (12
   * §3.9: "WAL flush" of the shutdown).
   */
  public void close() {
    stopping = true;
    Thread thread = writerThread;
    if (thread != null) {
      try {
        thread.join(TimeUnit.SECONDS.toMillis(10));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (thread.isAlive()) {
        LOG.warn("The WAL writer did not stop within 10 s");
      } else {
        LOG.info("WAL closed");
      }
    }
  }

  @Override
  public void destroy() {
    close();
  }

  private static final class Pending {
    final WalRecordType type;
    final byte[] bytes;
    final CompletableFuture<WalPosition> future = new CompletableFuture<>();
    WalPosition position;

    Pending(WalRecordType type, byte[] bytes) {
      this.type = type;
      this.bytes = bytes;
    }
  }
}
